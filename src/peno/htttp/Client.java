package peno.htttp;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import peno.htttp.impl.Consumer;
import peno.htttp.impl.PlayerInfo;
import peno.htttp.impl.PlayerRegister;
import peno.htttp.impl.PlayerRoll;
import peno.htttp.impl.PlayerState;
import peno.htttp.impl.RequestProvider;
import peno.htttp.impl.Requester;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.tools.json.JSONWriter;

/**
 * A client for communicating over the HTTTP protocol.
 */
public class Client {

	/*
	 * Constants
	 */
	public static final int nbPlayers = 4;
	public static final int joinExpiration = 2000;
	public static final int heartbeatFrequency = 2000;
	public static final int heartbeatExpiration = 5000;

	/*
	 * Communication
	 */
	private final Channel channel;
	private final Handler handler;
	private String joinQueue;
	private String publicQueue;
	private String teamQueue;
	private final RequestProvider requestProvider;

	/*
	 * Identifiers
	 */
	private final String gameID;

	/*
	 * Game state
	 */
	private GameState gameState = GameState.DISCONNECTED;
	private final PlayerInfo localPlayer;
	private final PlayerRegister players = new PlayerRegister();

	/*
	 * Rolls
	 */
	private final Map<String, Integer> playerNumbers = new HashMap<String, Integer>();
	private final Map<String, PlayerRoll> playerRolls = new HashMap<String, PlayerRoll>();

	/**
	 * Create a game client.
	 * 
	 * @param connection
	 *            The AMPQ connection for communication.
	 * @param handler
	 *            The event handler which listens to this client.
	 * @param gameID
	 *            The game identifier.
	 * @param playerID
	 *            The local player identifier.
	 * @throws IOException
	 */
	public Client(Connection connection, Handler handler, String gameID,
			String playerID) throws IOException {
		this.handler = handler;
		this.gameID = gameID;
		this.localPlayer = new PlayerInfo(UUID.randomUUID().toString(),
				playerID, PlayerState.NOT_READY);

		this.channel = connection.createChannel();
		this.requestProvider = new RequestProvider(channel);
		setup();
	}

	/**
	 * Get the client identifier.
	 * 
	 * <p>
	 * This is a pseudo-randomly generated UUID used to identify the client when
	 * connecting. It is used to prevent handling requests coming from the own
	 * client.
	 * </p>
	 */
	protected String getClientID() {
		return getLocalPlayer().getClientID();
	}

	/**
	 * Get the game identifier.
	 */
	public String getGameID() {
		return gameID;
	}

	/**
	 * Get the player identifier.
	 */
	public String getPlayerID() {
		return getLocalPlayer().getPlayerID();
	}

	/**
	 * Get the current state of the game.
	 */
	public GameState getGameState() {
		return gameState;
	}

	protected void setGameState(GameState gameState) {
		this.gameState = gameState;
	}

	/**
	 * Check whether this client is connected to a game.
	 */
	public boolean isConnected() {
		return getGameState() != GameState.DISCONNECTED;
	}

	/*
	 * Player tracking
	 */

	/**
	 * Get the number of players currently in the game.
	 */
	public int getNbPlayers() {
		synchronized (players) {
			return players.getNbPlayers();
		}
	}

	/**
	 * Check whether the game is currently full.
	 */
	public boolean isFull() {
		return getNbPlayers() >= nbPlayers;
	}

	private boolean hasPlayer(String playerID) {
		synchronized (players) {
			return players.hasPlayer(playerID);
		}
	}

	private PlayerInfo getPlayer(String playerID) {
		synchronized (players) {
			return players.getPlayer(playerID);
		}
	}

	private PlayerInfo getLocalPlayer() {
		return localPlayer;
	}

	private void addPlayer(PlayerInfo player) {
		synchronized (players) {
			players.addClient(player);
		}
	}

	private void removePlayer(String clientID, String playerID) {
		synchronized (players) {
			players.removeClient(clientID, playerID);
		}
	}

	/*
	 * Joining/leaving
	 */

	/**
	 * Join the game.
	 * 
	 * @param callback
	 *            A callback which receives the result of this request.
	 * @throws IllegalStateException
	 *             If this client is already connected to the game.
	 * @throws IOException
	 */
	public void join(final Callback<Void> callback)
			throws IllegalStateException, IOException {
		if (isConnected()) {
			throw new IllegalStateException("Already connected to game.");
		}

		// Reset
		resetGame();

		// Setup
		setGameState(GameState.JOINING);
		setupJoin();

		// Request to join
		new JoinRequester(callback).request(joinExpiration);
	}

	protected boolean canJoin(String clientID, String playerID) {
		switch (getGameState()) {
		case PLAYING:
			// Nobody can join while playing
			return false;
		case PAUSED:
			// Only missing players can join
			return hasPlayer(playerID);
		case JOINING:
		case WAITING:
			// Reject duplicate players
			if (hasPlayer(playerID))
				return false;
			// Reject when full
			if (isFull())
				return false;
			return true;
		default:
		}
		return false;
	}

	protected void joined() throws IOException {
		// Setup public queue
		setupPublic();
	}

	protected void playerJoined(String clientID, String playerID,
			boolean isReady) {
		// Report
		handler.playerJoined(playerID);
	}

	/**
	 * Check if this client is connected and joined to the game.
	 */
	public boolean isJoined() {
		return isConnected() && getGameState() != GameState.JOINING;
	}

	/**
	 * Leave this game.
	 * 
	 * @throws IOException
	 */
	public void leave() throws IOException {
		// Reset game
		resetGame();
		// Shut down everything
		try {
			shutdownJoin();
			shutdownPublic();
			shutdownTeam();
			// Publish "leave"
			Map<String, Object> message = newMessage();
			message.put("clientID", getClientID());
			publish("leave", message);
		} catch (ShutdownSignalException e) {
		}
	}

	protected void playerLeft(String clientID, String playerID) {
		// Report
		handler.playerLeft(playerID);

		switch (getGameState()) {
		case WAITING:
			// Remove player
			removePlayer(clientID, playerID);
			break;
		case PLAYING:
		case PAUSED:
			if (hasPlayer(playerID)) {
				// Player went missing
				getPlayer(playerID).setState(PlayerState.DISCONNECTED);
				// Paused
				paused();
			}
			break;
		default:
			break;
		}
	}

	/*
	 * Game state
	 */

	/**
	 * Check if the game is started and running.
	 */
	public boolean isPlaying() {
		return getGameState() == GameState.PLAYING;
	}

	/**
	 * Check whether the game is currently paused.
	 */
	public boolean isPaused() {
		return getGameState() == GameState.PAUSED;
	}

	/**
	 * Check if the game can be started.
	 */
	public boolean canStart() {
		switch (getGameState()) {
		case WAITING:
		case PAUSED:
			// Game must be full
			if (!isFull())
				return false;
			// All players must be ready
			for (PlayerInfo playerInfo : players) {
				if (!playerInfo.isReady())
					return false;
			}
			return true;
		default:
			return false;
		}
	}

	/**
	 * Check if the local player is ready to play.
	 */
	public boolean isReady() {
		return getLocalPlayer().isReady();
	}

	/**
	 * Set whether the local player is ready to play.
	 * 
	 * @param isReady
	 *            True if the local player is ready.
	 * @throws IOException
	 */
	public void setReady(boolean isReady) throws IOException {
		PlayerInfo playerInfo = getLocalPlayer();
		PlayerState newState = isReady ? PlayerState.READY
				: PlayerState.NOT_READY;

		if (newState != playerInfo.getState()) {
			// Update state
			playerInfo.setState(newState);

			// Publish updated state
			Map<String, Object> message = newMessage();
			message.put("isReady", isReady);
			publish("ready", message);

			// Try to start automatically
			tryStart();
		}
	}

	protected void start() throws IllegalStateException, IOException {
		if (!isJoined()) {
			throw new IllegalStateException("Not joined in the game.");
		}
		if (isPlaying()) {
			throw new IllegalStateException("Game already started.");
		}
		if (!canStart()) {
			throw new IllegalStateException("Cannot start the game.");
		}

		// Publish
		publish("start", null);
	}

	protected void tryStart() throws IOException {
		if (isJoined() && !isPlaying() && canStart()) {
			start();
		}
	}

	protected synchronized void started() {
		if (isPlaying())
			return;

		if (needRoll()) {
			try {
				// Roll for player numbers
				roll();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			// Already rolled
			// Update game state
			setGameState(GameState.PLAYING);
			// call handler
			handler.gameStarted();
		}
	}

	/**
	 * Stop the game completely.
	 * 
	 * @throws IllegalStateException
	 *             If not joined in the game.
	 * @throws IOException
	 */
	public void stop() throws IllegalStateException, IOException {
		if (!isJoined()) {
			throw new IllegalStateException("Not joined in the game.");
		}

		if (getGameState() == GameState.WAITING) {
			// Game already stopped
			return;
		}

		// Publish
		publish("stop", null);
	}

	protected synchronized void stopped() throws IOException {
		if (!isPlaying())
			return;

		// Update game state
		setGameState(GameState.WAITING);
		// Stop team communication
		shutdownTeam();
		// Clear player rolls and numbers
		clearPlayerNumbers();
		// Set as not ready
		setReady(false);
		// Call handler
		handler.gameStopped();
	}

	/**
	 * Pause the game.
	 * 
	 * <p>
	 * Call {@link #setReady(boolean)} when ready again to continue the game.
	 * </p>
	 * 
	 * @throws IllegalStateException
	 *             If not joined in the game, or if not playing.
	 * @throws IOException
	 */
	public void pause() throws IOException, IllegalStateException {
		if (!isJoined()) {
			throw new IllegalStateException("Not joined in the game.");
		}
		if (!isPlaying()) {
			throw new IllegalStateException("Can only pause while playing.");
		}
		if (isPaused()) {
			// Game already paused
			return;
		}

		// Set as not ready
		setReady(false);

		// Publish
		publish("pause", null);
	}

	protected synchronized void paused() {
		if (isPaused())
			return;

		// Update game state
		setGameState(GameState.PAUSED);
		// Call handler
		handler.gamePaused();
	}

	/*
	 * Player number rolling
	 */

	/**
	 * Get the local player's number to identify its object.
	 * 
	 * @throws IllegalStateException
	 *             If not playing.
	 */
	public int getPlayerNumber() throws IllegalStateException {
		if (!isPlaying()) {
			throw new IllegalStateException(
					"Can only retrieve player number while playing.");
		}
		return playerNumbers.get(getPlayerID());
	}

	protected boolean needRoll() {
		return playerNumbers.isEmpty();
	}

	protected void roll() throws IOException {
		if (!needRoll())
			return;

		// Roll for player number
		int roll = new Random().nextInt();

		// Store own roll
		playerRolls.put(getPlayerID(), new PlayerRoll(getPlayerID(), roll));

		// Publish
		Map<String, Object> message = newMessage();
		message.put("roll", roll);
		publish("roll", message);
	}

	protected void receivedRoll(String playerID, int roll) {
		// Store roll
		playerRolls.put(playerID, new PlayerRoll(playerID, roll));

		if (playerRolls.size() == nbPlayers) {
			// Sort rolls
			sortPlayerRolls();
			// Start
			started();
		}
	}

	private void sortPlayerRolls() {
		// Sort rolls
		PlayerRoll[] rolls = playerRolls.values().toArray(
				new PlayerRoll[nbPlayers]);
		Arrays.sort(rolls);

		// Generate player numbers
		playerNumbers.clear();
		for (int i = 0; i < nbPlayers; ++i) {
			playerNumbers.put(rolls[i].getPlayerID(), i + 1);
		}
	}

	protected void replacePlayerNumbers(Map<String, Integer> numbers) {
		clearPlayerNumbers();
		playerNumbers.putAll(numbers);
	}

	private void clearPlayerNumbers() {
		playerRolls.clear();
		playerNumbers.clear();
	}

	/*
	 * Notifications
	 */

	/**
	 * Publish the updated position of the local player.
	 * 
	 * <p>
	 * The standard coordinate system has the X-axis running from left to right
	 * and the Y-axis from bottom to top. The angle of orientation is measured
	 * clockwise from the positive X-axis to the positive Y-axis.
	 * </p>
	 * 
	 * @param x
	 *            The X-coordinate of the position.
	 * @param y
	 *            The Y-coordinate of the position.
	 * @param angle
	 *            The angle of rotation.
	 * @throws IllegalStateException
	 *             If not joined in the game.
	 * @throws IOException
	 */
	public void updatePosition(double x, double y, double angle)
			throws IllegalStateException, IOException {
		if (!isJoined()) {
			throw new IllegalStateException("Not joined in the game.");
		}

		Map<String, Object> message = newMessage();
		message.put("x", x);
		message.put("y", y);
		message.put("angle", angle);
		publish("position", message);
	}

	/**
	 * Publish a notification indicating that the local player has found their
	 * object.
	 * 
	 * @throws IllegalStateException
	 *             If not playing.
	 * @throws IOException
	 */
	public void foundObject() throws IllegalStateException, IOException {
		if (!isPlaying()) {
			throw new IllegalStateException(
					"Cannot find object when not playing.");
		}

		// Publish
		publish("found", null);
	}

	/*
	 * Teams
	 */

	/**
	 * Join the given team.
	 * 
	 * @param teamId
	 *            The team identifier.
	 * @throws IllegalStateException
	 *             If not playing.
	 * @throws IOException
	 */
	public void joinTeam(int teamId) throws IOException {
		if (!isPlaying()) {
			throw new IllegalStateException(
					"Cannot join team when not playing.");
		}

		// Setup team
		setupTeam(teamId);
	}

	/*
	 * Setup/shutdown
	 */

	private void setup() throws IOException {
		// Reset game
		resetGame();
		// Declare exchange
		channel.exchangeDeclare(getGameID(), "topic");
	}

	private void setupJoin() throws IOException {
		// Declare and bind
		joinQueue = channel.queueDeclare().getQueue();
		channel.queueBind(joinQueue, getGameID(), "*");

		// Attach consumers
		channel.basicConsume(joinQueue, true, new JoinLeaveHandler(channel));
	}

	private void shutdownJoin() throws IOException {
		shutdownQueue(joinQueue);
	}

	private void setupPublic() throws IOException {
		// Declare and bind
		publicQueue = channel.queueDeclare().getQueue();
		channel.queueBind(publicQueue, getGameID(), "*");

		// Attach consumers
		channel.basicConsume(publicQueue, true, new PublicHandler(channel));
	}

	private void shutdownPublic() throws IOException {
		shutdownQueue(publicQueue);
	}

	private void setupTeam(int teamId) throws IOException {
		// Declare and bind
		teamQueue = channel.queueDeclare().getQueue();
		channel.queueBind(teamQueue, getGameID(), "team." + teamId + ".*");

		// TODO Attach consumers
	}

	private void shutdownTeam() throws IOException {
		shutdownQueue(teamQueue);
	}

	private void shutdownQueue(String queue) throws IOException {
		// Delete queue (also cancels attached consumers)
		if (queue != null) {
			try {
				channel.queueDelete(queue);
			} catch (IOException | ShutdownSignalException e) {
				// Ignore
			}
		}
	}

	/**
	 * Shutdown this client.
	 * 
	 * <p>
	 * Leave the game and then close the opened channel.
	 * </p>
	 */
	public void shutdown() {
		try {
			// Leave the game
			leave();
		} catch (IOException e) {
		}
		try {
			// Close channel
			channel.close();
		} catch (IOException | ShutdownSignalException e) {
		}
	}

	private void resetGame() {
		// Reset game state
		setGameState(GameState.DISCONNECTED);
		// Clear player rolls and numbers
		clearPlayerNumbers();
		// Set as not ready
		getLocalPlayer().setState(PlayerState.NOT_READY);
		// Only retain local player
		synchronized (players) {
			players.clear();
			addPlayer(getLocalPlayer());
		}
	}

	/*
	 * Helpers
	 */

	protected void publish(String routingKey, Map<String, Object> message,
			BasicProperties props) throws IOException {
		channel.basicPublish(getGameID(), routingKey, props,
				prepareMessage(message));
	}

	protected void publish(String routingKey, Map<String, Object> message)
			throws IOException {
		publish(routingKey, message, defaultProps().build());
	}

	protected void reply(BasicProperties requestProps,
			Map<String, Object> message) throws IOException {
		BasicProperties props = defaultProps().correlationId(
				requestProps.getCorrelationId()).build();
		channel.basicPublish("", requestProps.getReplyTo(), props,
				prepareMessage(message));
	}

	private AMQP.BasicProperties.Builder defaultProps() {
		return new AMQP.BasicProperties.Builder().timestamp(new Date())
				.contentType("text/plain").deliveryMode(1);
	}

	protected Map<String, Object> newMessage() {
		Map<String, Object> message = new HashMap<String, Object>();

		// Add player ID to message
		message.put("playerID", getPlayerID());

		return message;
	}

	protected byte[] prepareMessage(Map<String, Object> message) {
		// Default message
		if (message == null) {
			message = newMessage();
		}

		// Serialize map as JSON object
		return new JSONWriter().write(message).getBytes();
	}

	/**
	 * Requests a join and handles the responses.
	 */
	private class JoinRequester extends Requester {

		private final Callback<Void> callback;
		private volatile boolean isDone = false;

		public JoinRequester(Callback<Void> callback) {
			super(channel, requestProvider);
			this.callback = callback;
		}

		public void request(int timeout) throws IOException {
			// Mark as working
			isDone = false;

			// Publish "join" with own player info
			PlayerInfo playerInfo = getLocalPlayer();
			Map<String, Object> message = newMessage();
			message.put("clientID", getClientID());
			message.put("isReady", playerInfo.isReady());

			request(getGameID(), "join", prepareMessage(message), timeout);
		}

		@Override
		public void handleResponse(Map<String, Object> message,
				BasicProperties props) {
			// Ignore when done
			if (isDone)
				return;

			boolean result = (Boolean) message.get("result");
			if (result) {
				// Accepted by peer
				String clientID = (String) message.get("clientID");
				String playerID = (String) message.get("playerID");
				Boolean isReady = (Boolean) message.get("isReady");

				// Store player
				addPlayer(new PlayerInfo(clientID, playerID,
						isReady ? PlayerState.READY : PlayerState.NOT_READY));
				// Set game state if valid
				String gameState = (String) message.get("gameState");
				if (gameState != null) {
					setGameState(GameState.valueOf(gameState));
				}

				// Store player numbers if set
				@SuppressWarnings("unchecked")
				Map<String, Integer> playerNumbers = (Map<String, Integer>) message
						.get("playerNumbers");
				if (playerNumbers != null) {
					replacePlayerNumbers(playerNumbers);
				}

				if (isFull()) {
					// All players responded
					// Short-circuit timeout
					success();
				}
			} else {
				// Rejected by peer
				failure();
			}
		}

		@Override
		public void handleTimeout() {
			if (!isDone) {
				// No response, first player in game
				// Set game state if not set yet
				if (!isJoined()) {
					setGameState(GameState.WAITING);
				}
				// Join was successful
				success();
			}
		}

		private void success() {
			try {
				// Cancel request
				cancel();
				// Handle join
				joined();
				// Report success
				callback.onSuccess(null);
			} catch (IOException e) {
				callback.onFailure(e);
			}
		}

		private void failure() {
			try {
				// Cancel request
				cancel();
				// Leave
				leave();
			} catch (IOException e) {
				callback.onFailure(e);
			}

			// Report failure
			callback.onFailure(new Exception("Join request rejected"));
		}

		private void cancel() throws IOException {
			// Mark as done
			isDone = true;
			// Cancel request
			cancelRequest();
		}

	}

	/**
	 * Handles join requests.
	 */
	private class JoinLeaveHandler extends Consumer {

		public JoinLeaveHandler(Channel channel) {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message,
				BasicProperties props) throws IOException {
			// Read player info
			String clientID = (String) message.get("clientID");
			String playerID = (String) message.get("playerID");

			// Ignore local messages
			if (getClientID().equals(clientID))
				return;

			if (topic.equals("join")) {
				// Prepare reply
				PlayerInfo playerInfo = getLocalPlayer();
				Map<String, Object> reply = newMessage();

				// Check if accepted
				boolean isAccepted = canJoin(clientID, playerID);
				reply.put("result", isAccepted);

				// Store player
				boolean isReady = (Boolean) message.get("isReady");
				addPlayer(new PlayerInfo(clientID, playerID,
						isReady ? PlayerState.READY : PlayerState.NOT_READY));

				if (isAccepted) {
					// Accept
					playerJoined(clientID, playerID, isReady);
					// Report state
					reply.put("isReady", playerInfo.isReady());
					reply.put("gameState", isJoined() ? getGameState().name()
							: null);
					if (!needRoll()) {
						reply.put("playerNumbers", playerNumbers);
					}
				} else {
					// Reject
				}

				// Send reply
				reply(props, reply);
			} else if (topic.equals("leave")) {
				// Handle player left
				playerLeft(clientID, playerID);
			}
		}

	}

	/**
	 * Handles public broadcasts.
	 */
	private class PublicHandler extends Consumer {

		public PublicHandler(Channel channel) {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message,
				BasicProperties props) throws IOException {
			String playerID = (String) message.get("playerID");
			if (topic.equals("ready")) {
				// Update player ready state
				boolean isReady = (Boolean) message.get("isReady");
				getPlayer(playerID).setState(
						isReady ? PlayerState.READY : PlayerState.NOT_READY);
			} else if (topic.equals("start")) {
				// Handle start
				started();
			} else if (topic.equals("stop")) {
				// Handle stopped
				stopped();
			} else if (topic.equals("pause")) {
				// Handle paused
				paused();
			} else if (topic.equals("roll")) {
				// Handle roll
				int roll = (Integer) message.get("roll");
				receivedRoll(playerID, roll);
			} else if (topic.equals("found")) {
				// Handle object found
				handler.playerFoundObject(playerID);
			}
		}

	}

}
