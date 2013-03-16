package peno.htttp;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import peno.htttp.impl.Consumer;
import peno.htttp.impl.NamedThreadFactory;
import peno.htttp.impl.PlayerState;
import peno.htttp.impl.PlayerRegister;
import peno.htttp.impl.PlayerRoll;
import peno.htttp.impl.RequestProvider;
import peno.htttp.impl.Requester;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.tools.json.JSONWriter;

/**
 * A client for playing a game over the HTTTP protocol.
 */
public class PlayerClient {

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
	private final Connection connection;
	private Channel channel;
	private RequestProvider requestProvider;
	private final PlayerHandler handler;
	private Consumer joinConsumer;
	private Consumer publicConsumer;
	private Consumer teamConsumer;

	/*
	 * Identifiers
	 */
	private final String gameID;

	/*
	 * Game state
	 */
	private volatile GameState gameState = GameState.DISCONNECTED;
	private final PlayerState localPlayer;
	private final PlayerRegister players = new PlayerRegister();

	/*
	 * Rolls
	 */
	private final Map<String, Integer> playerNumbers = new HashMap<String, Integer>();
	private final Map<String, PlayerRoll> playerRolls = new HashMap<String, PlayerRoll>();

	/*
	 * Heart beat
	 */
	private ScheduledExecutorService heartbeatExecutor;
	private ScheduledFuture<?> heartbeatTask;
	private static final ThreadFactory heartbeatFactory = new NamedThreadFactory("HTTTP-HeartBeat-%d");

	/**
	 * Create a game client.
	 * 
	 * @param connection
	 *            The AMQP connection for communication.
	 * @param handler
	 *            The event handler which listens to this client.
	 * @param gameID
	 *            The game identifier.
	 * @param playerID
	 *            The local player identifier.
	 * @throws IOException
	 */
	public PlayerClient(Connection connection, PlayerHandler handler, String gameID, String playerID)
			throws IOException {
		this.connection = connection;
		this.handler = handler;
		this.gameID = gameID;

		String clientID = UUID.randomUUID().toString();
		this.localPlayer = new PlayerState(clientID, playerID);
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
	private String getClientID() {
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

	private void setGameState(GameState gameState) {
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
	 * Get the number of players currently in the game, including the local
	 * player.
	 */
	public int getNbPlayers() {
		synchronized (players) {
			return players.getNbPlayers();
		}
	}

	/**
	 * Get the player identifiers of all confirmed players.
	 * 
	 * <p>
	 * The returned set also includes the local player identifier.
	 * </p>
	 */
	public Set<String> getPlayers() {
		Set<String> playerIDs = new HashSet<String>();
		synchronized (players) {
			for (PlayerState player : players.getConfirmed()) {
				playerIDs.add(player.getPlayerID());
			}
		}
		return playerIDs;
	}

	/**
	 * Check whether the game is currently full.
	 */
	public boolean isFull() {
		return getNbPlayers() >= nbPlayers;
	}

	private boolean hasPlayer(String clientID, String playerID) {
		synchronized (players) {
			return players.isConfirmed(clientID, playerID);
		}
	}

	private PlayerState getPlayer(String playerID) {
		synchronized (players) {
			return players.getConfirmed(playerID);
		}
	}

	private PlayerState getLocalPlayer() {
		return localPlayer;
	}

	private void confirmPlayer(String clientID, String playerID, boolean isReady) {
		synchronized (players) {
			PlayerState player = players.getVoted(clientID, playerID);
			if (player == null) {
				player = new PlayerState(clientID, playerID);
			}
			player.setReady(isReady);
			players.confirm(player);
		}
	}

	private void votePlayer(String clientID, String playerID) {
		synchronized (players) {
			players.vote(new PlayerState(clientID, playerID));
		}
	}

	private void removePlayer(String clientID, String playerID) {
		synchronized (players) {
			players.remove(clientID, playerID);
		}
	}

	private boolean isMissingPlayer(String playerID) {
		synchronized (players) {
			return players.isMissing(playerID);
		}
	}

	private Set<String> getMissingPlayers() {
		synchronized (players) {
			return players.getMissing();
		}
	}

	private void setMissingPlayer(String missingPlayer) {
		synchronized (players) {
			players.setMissing(missingPlayer);
		}
	}

	private void setMissingPlayers(Iterable<String> missingPlayers) {
		synchronized (players) {
			for (String missingPlayer : missingPlayers) {
				players.setMissing(missingPlayer);
			}
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
	public void join(final Callback<Void> callback) throws IllegalStateException, IOException {
		if (isConnected()) {
			throw new IllegalStateException("Already connected to game.");
		}

		// Setup
		setup();

		// Setup join
		setGameState(GameState.JOINING);
		setupJoin();

		// Start heart beats
		heartbeatStart();

		// Request to join
		new JoinRequester(callback).request(joinExpiration);
	}

	/**
	 * Check if the given player can join.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	protected boolean canJoin(String clientID, String playerID) {
		switch (getGameState()) {
		case PLAYING:
			// Nobody can join while playing
			return false;
		case PAUSED:
			// Only missing players can join
			return isMissingPlayer(playerID);
		case JOINING:
		case STARTING:
		case WAITING:
			// Reject duplicate players
			if (!players.canJoin(clientID, playerID))
				return false;
			// Reject when full
			if (isFull())
				return false;
			return true;
		default:
		}
		return false;
	}

	private void joined() throws IOException {
		// Setup public queue
		setupPublic();
		// Try to roll
		tryRoll();
	}

	private void playerJoined(String clientID, String playerID, boolean isReady) throws IOException {
		// Confirm player
		confirmPlayer(clientID, playerID, isReady);
		// Call handler
		handler.playerJoined(playerID);
		// Try to roll
		tryRoll();
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
		disconnect(DisconnectReason.LEAVE);
	}

	private void disconnect(DisconnectReason reason) throws IOException {
		// Reset game
		resetGame();
		// Stop request provider
		requestProvider.terminate();
		requestProvider = null;
		try {
			// Shut down queues
			shutdownJoin();
			shutdownPublic();
			shutdownTeam();
			// Stop heart beats
			heartbeatStop();
			// Publish leave
			Map<String, Object> message = newMessage();
			message.put("clientID", getClientID());
			message.put("reason", reason.name());
			publish("disconnect", message);
		} catch (IOException e) {
			throw e;
		} catch (ShutdownSignalException e) {
		} finally {
			try {
				// Shut down channel
				channel.close();
			} catch (IOException e) {
			} catch (ShutdownSignalException e) {
			} finally {
				channel = null;
			}
		}
	}

	private void playerDisconnected(String clientID, String playerID, DisconnectReason reason) {
		// Call handler
		handler.playerDisconnected(playerID, reason);

		switch (getGameState()) {
		case WAITING:
		case STARTING:
			// Revert to waiting
			setGameState(GameState.WAITING);
			// Remove player
			removePlayer(clientID, playerID);
			// Invalidate player numbers
			clearPlayerNumbers();
			break;
		case PLAYING:
		case PAUSED:
			if (hasPlayer(clientID, playerID)) {
				// Player went missing
				setMissingPlayer(playerID);
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
		case STARTING:
		case PAUSED:
			// Game must be full
			if (!isFull())
				return false;
			// All players must be ready
			for (PlayerState player : players.getConfirmed()) {
				if (!player.isReady())
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
	 * @throws IllegalStateException
	 *             If not joined.
	 * @throws IOException
	 */
	public void setReady(boolean isReady) throws IOException {
		if (!isJoined()) {
			throw new IllegalStateException("Not joined in the game.");
		}

		if (isReady != isReady()) {
			// Publish updated state
			Map<String, Object> message = newMessage();
			message.put("isReady", isReady);
			publish("ready", message);

			// Handle local player ready
			playerReady(getPlayerID(), isReady);
		}
	}

	private void playerReady(String playerID, boolean isReady) throws IOException {
		// Set ready state
		getPlayer(playerID).setReady(isReady);
		// Call handler
		handler.playerReady(playerID, isReady);

		if (isReady) {
			// Try to start
			tryStart();
		} else {
			// TODO Should we pause here when playing?
			// Related: should we replace pause() with setReady(false)?
		}
	}

	/**
	 * Start the game.
	 * 
	 * @throws IllegalStateException
	 *             If not joined, if already started, if unable to start or if
	 *             player numbers not determined yet.
	 * @throws IOException
	 */
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
		if (!hasPlayerNumber()) {
			throw new IllegalStateException("Player numbers not determined yet.");
		}

		// Publish
		publish("start", null);
	}

	private void tryStart() throws IOException {
		if (isJoined() && !isPlaying() && canStart() && hasPlayerNumber()) {
			start();
		}
	}

	private synchronized void started() {
		if (isPlaying())
			return;

		// Update game state
		setGameState(GameState.PLAYING);
		// Call handler
		handler.gameStarted();
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

	private synchronized void stopped() throws IOException {
		if (!isPlaying())
			return;

		// Update game state
		setGameState(GameState.WAITING);
		// Stop team communication
		shutdownTeam();
		// Clear player rolls and numbers
		clearPlayerNumbers();
		// Clear missing players
		players.clearMissing();
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

		// Publish
		publish("pause", null);

		// Set as not ready
		setReady(false);
	}

	private synchronized void paused() {
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
	 *             If not determined yet.
	 */
	public int getPlayerNumber() throws IllegalStateException {
		if (!hasPlayerNumber()) {
			throw new IllegalStateException("Player number not determined yet.");
		}
		return playerNumbers.get(getPlayerID());
	}

	/**
	 * Check if the player numbers have been determined.
	 */
	public boolean hasPlayerNumber() {
		switch (getGameState()) {
		case PAUSED:
		case PLAYING:
		case STARTING:
			return (playerNumbers.size() == nbPlayers);
		default:
			return false;
		}
	}

	/**
	 * Check if the player numbers can be rolled.
	 */
	public boolean canRoll() {
		return getGameState() == GameState.WAITING && isFull();
	}

	/**
	 * Roll for player numbers.
	 * 
	 * @throws IllegalStateException
	 *             If not joined, if already rolled or started or if not all
	 *             players have joined yet.
	 * @throws IOException
	 */
	protected void roll() throws IOException {
		if (!isJoined()) {
			throw new IllegalStateException("Not joined in the game.");
		}
		if (isPlaying()) {
			throw new IllegalStateException("Game already started.");
		}
		if (hasPlayerNumber()) {
			throw new IllegalStateException("Already rolled for player numbers.");
		}
		if (!canRoll()) {
			throw new IllegalStateException("Not all players have joined yet.");
		}

		// Roll own number if needed
		if (!hasRolledOwn()) {
			rollOwn();
		}
		// Publish own number
		rollPublish();
	}

	/**
	 * Attempt to roll for player numbers.
	 * 
	 * @throws IOException
	 */
	protected void tryRoll() throws IOException {
		if (isJoined() && !isPlaying() && !hasPlayerNumber() && canRoll()) {
			roll();
		}
	}

	private boolean hasRolledOwn() {
		return playerRolls.containsKey(getPlayerID());
	}

	private void rollOwn() {
		// Roll for player number
		int roll = new Random().nextInt();
		// Store own roll
		playerRolls.put(getPlayerID(), new PlayerRoll(getPlayerID(), roll));
	}

	private void rollPublish() throws IOException {
		// Publish own roll
		int roll = playerRolls.get(getPlayerID()).getRoll();
		Map<String, Object> message = newMessage();
		message.put("roll", roll);
		publish("roll", message);
	}

	private void rollReceived(String playerID, int roll) throws IOException {
		// Store roll
		playerRolls.put(playerID, new PlayerRoll(playerID, roll));

		// Roll and publish own number if needed
		if (!hasRolledOwn()) {
			rollOwn();
			rollPublish();
		}

		// Check if done
		if (!hasPlayerNumber() && playerRolls.size() == nbPlayers) {
			// Sort rolls and retrieve player numbers
			sortPlayerRolls();
			// Set as starting
			setGameState(GameState.STARTING);
			// Call handler
			handler.gameRolled(getPlayerNumber());
		}
	}

	private void sortPlayerRolls() {
		// Sort rolls
		PlayerRoll[] rolls = playerRolls.values().toArray(new PlayerRoll[nbPlayers]);
		Arrays.sort(rolls);

		// Generate player numbers
		playerNumbers.clear();
		for (int i = 0; i < nbPlayers; ++i) {
			playerNumbers.put(rolls[i].getPlayerID(), i);
		}
	}

	private void replacePlayerNumbers(Map<String, Integer> numbers) {
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
	 *             If not playing.
	 * @throws IOException
	 */
	public void updatePosition(double x, double y, double angle) throws IllegalStateException, IOException {
		if (!isPlaying()) {
			throw new IllegalStateException("Cannot update position when not playing.");
		}

		Map<String, Object> message = newMessage();
		message.put("playerNumber", getPlayerNumber());
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
			throw new IllegalStateException("Cannot find object when not playing.");
		}

		// Publish
		Map<String, Object> message = newMessage();
		message.put("playerNumber", getPlayerNumber());
		publish("found", message);
	}

	/*
	 * Heart beat
	 */

	protected void heartbeatStart() {
		// Stop if still running
		heartbeatStop();

		// Setup executor
		heartbeatExecutor = Executors.newScheduledThreadPool(1, heartbeatFactory);

		// Start beating
		heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(new HeartbeatTask(), 0, heartbeatFrequency,
				TimeUnit.MILLISECONDS);
	}

	protected void heartbeatStop() {
		// Cancel task
		if (heartbeatTask != null) {
			heartbeatTask.cancel(false);
			heartbeatTask = null;
		}
		// Shut down executor
		if (heartbeatExecutor != null) {
			if (!heartbeatExecutor.isShutdown()) {
				heartbeatExecutor.shutdown();
			}
			heartbeatExecutor = null;
		}
	}

	private void heartbeatPublish() throws IOException {
		// Update own heartbeat
		long timestamp = System.currentTimeMillis();
		getLocalPlayer().setLastHeartbeat(timestamp);

		// Publish
		publish("heartbeat", null);
	}

	private void heartbeatCheck() throws IOException {
		final long limit = System.currentTimeMillis() - heartbeatExpiration;
		// Clone for safe iteration
		final PlayerState[] confirmed = players.getConfirmed().toArray(new PlayerState[0]);
		// Check all confirmed players
		for (PlayerState player : confirmed) {
			final long lastHeartbeat = player.getLastHeartbeat();
			if (lastHeartbeat > 0 && lastHeartbeat < limit) {
				// Heart beat expired, report missing
				heartbeatMissing(player);
			}
		}
	}

	private void heartbeatMissing(PlayerState player) throws IOException {
		// Publish leave for player
		Map<String, Object> message = newMessage();
		message.put("playerID", player.getPlayerID());
		message.put("clientID", player.getClientID());
		message.put("reason", DisconnectReason.TIMEOUT.name());
		publish("disconnect", message);
	}

	private void heartbeatReceived(String playerID) {
		PlayerState player = getPlayer(playerID);
		if (player != null) {
			player.setLastHeartbeat(System.currentTimeMillis());
		}
	}

	private class HeartbeatTask implements Runnable {

		@Override
		public void run() {
			try {
				// Publish and check
				heartbeatPublish();
				if (isJoined()) {
					heartbeatCheck();
				}
			} catch (IOException e) {
				// Bail out
				heartbeatStop();
			}
		}

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
			throw new IllegalStateException("Cannot join team when not playing.");
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

		// Create channel
		channel = connection.createChannel();
		// Declare exchange
		channel.exchangeDeclare(getGameID(), "topic");

		// Setup request provider
		requestProvider = new RequestProvider();
	}

	private void setupJoin() throws IOException {
		joinConsumer = new JoinLeaveConsumer(channel);
		joinConsumer.bind(getGameID(), "*");
	}

	private void shutdownJoin() {
		if (joinConsumer != null) {
			joinConsumer.terminate();
		}
	}

	private void setupPublic() throws IOException {
		publicConsumer = new PublicConsumer(channel);
		publicConsumer.bind(getGameID(), "*");
	}

	private void shutdownPublic() throws IOException {
		if (publicConsumer != null) {
			publicConsumer.terminate();
		}
	}

	private void setupTeam(int teamId) throws IOException {
		teamConsumer = new TeamConsumer(channel);
		teamConsumer.bind(getGameID(), "team." + teamId + ".*");
	}

	private void shutdownTeam() throws IOException {
		if (teamConsumer != null) {
			teamConsumer.terminate();
		}
	}

	private void resetGame() {
		// Reset game state
		setGameState(GameState.DISCONNECTED);
		// Clear player rolls and numbers
		clearPlayerNumbers();
		// Set as not ready
		getLocalPlayer().setReady(false);
		// Only retain local player
		synchronized (players) {
			players.clear();
			players.confirm(getLocalPlayer());
		}
	}

	private void readGameState(Map<String, Object> message) {
		// Game state (if valid)
		String gameState = (String) message.get("gameState");
		if (gameState != null) {
			setGameState(GameState.valueOf(gameState));
		}

		// Player numbers (if valid)
		@SuppressWarnings("unchecked")
		Map<String, Integer> playerNumbers = (Map<String, Integer>) message.get("playerNumbers");
		if (playerNumbers != null) {
			replacePlayerNumbers(playerNumbers);
		}

		// Missing players (if valid)
		@SuppressWarnings("unchecked")
		Iterable<String> missingPlayers = (Iterable<String>) message.get("missingPlayers");
		if (missingPlayers != null) {
			setMissingPlayers(missingPlayers);
		}
	}

	private void writeGameState(Map<String, Object> message) {
		// Game state
		if (isJoined()) {
			message.put("gameState", getGameState().name());
		}
		// Player numbers
		if (hasPlayerNumber()) {
			message.put("playerNumbers", playerNumbers);
		}
		// Missing players
		Set<String> missingPlayers = getMissingPlayers();
		if (!missingPlayers.isEmpty()) {
			message.put("missingPlayers", missingPlayers);
		}
	}

	/*
	 * Helpers
	 */

	protected void publish(String routingKey, Map<String, Object> message, BasicProperties props) throws IOException {
		channel.basicPublish(getGameID(), routingKey, props, prepareMessage(message));
	}

	protected void publish(String routingKey, Map<String, Object> message) throws IOException {
		publish(routingKey, message, defaultProps().build());
	}

	protected void reply(BasicProperties requestProps, Map<String, Object> message) throws IOException {
		BasicProperties props = defaultProps().correlationId(requestProps.getCorrelationId()).build();
		channel.basicPublish("", requestProps.getReplyTo(), props, prepareMessage(message));
	}

	private AMQP.BasicProperties.Builder defaultProps() {
		return new AMQP.BasicProperties.Builder().timestamp(new Date()).contentType("text/plain").deliveryMode(1);
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

		public JoinRequester(Callback<Void> callback) throws IOException {
			super(channel, requestProvider);
			this.callback = callback;
		}

		public void request(int timeout) throws IOException {
			// Mark as working
			isDone = false;

			// Publish join with own player info
			Map<String, Object> message = createMessage();
			request(getGameID(), "join", prepareMessage(message), timeout);
		}

		@Override
		public void handleResponse(Map<String, Object> message, BasicProperties props) {
			// Ignore when done
			if (isDone)
				return;

			boolean result = (Boolean) message.get("result");
			if (result) {
				// Accepted by peer
				String clientID = (String) message.get("clientID");
				String playerID = (String) message.get("playerID");
				boolean isReady = (Boolean) message.get("isReady");
				boolean isJoined = (Boolean) message.get("isJoined");

				// Store player
				if (isJoined) {
					confirmPlayer(clientID, playerID, isReady);
				} else {
					votePlayer(clientID, playerID);
				}

				// Read game state
				readGameState(message);

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
				// Publish joined
				publish("joined", createMessage());
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
				// Disconnect
				disconnect(DisconnectReason.REJECT);
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

		private Map<String, Object> createMessage() {
			PlayerState player = getLocalPlayer();
			Map<String, Object> message = newMessage();
			message.put("clientID", player.getClientID());
			message.put("isReady", player.isReady());
			return message;
		}

	}

	/**
	 * Handles join requests.
	 */
	private class JoinLeaveConsumer extends Consumer {

		public JoinLeaveConsumer(Channel channel) throws IOException {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message, BasicProperties props) throws IOException {
			// Read player info
			String clientID = (String) message.get("clientID");
			String playerID = (String) message.get("playerID");

			// Ignore local messages
			if (getClientID().equals(clientID))
				return;

			if (topic.equals("join")) {
				// Prepare reply
				PlayerState player = getLocalPlayer();
				Map<String, Object> reply = newMessage();
				// Check if accepted
				boolean isAccepted = canJoin(clientID, playerID);
				reply.put("result", isAccepted);
				// Store player
				votePlayer(clientID, playerID);
				// Add data if accepted
				if (isAccepted) {
					// Report own player state
					reply.put("clientID", player.getClientID());
					reply.put("isReady", player.isReady());
					reply.put("isJoined", isJoined());
					// Report game state
					writeGameState(reply);
				}
				// Send reply
				reply(props, reply);
				// Call handler
				handler.playerJoining(playerID);
			} else if (topic.equals("joined")) {
				// Player joined
				boolean isReady = (Boolean) message.get("isReady");
				playerJoined(clientID, playerID, isReady);
			} else if (topic.equals("disconnect")) {
				// Player disconnected
				DisconnectReason reason = DisconnectReason.valueOf((String) message.get("reason"));
				playerDisconnected(clientID, playerID, reason);
			} else if (topic.equals("roll")) {
				// Player rolled their number
				int roll = (Integer) message.get("roll");
				rollReceived(playerID, roll);
			}
		}

	}

	/**
	 * Handles public broadcasts.
	 */
	private class PublicConsumer extends Consumer {

		public PublicConsumer(Channel channel) throws IOException {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message, BasicProperties props) throws IOException {
			String playerID = (String) message.get("playerID");
			if (topic.equals("ready")) {
				// Player ready
				boolean isReady = (Boolean) message.get("isReady");
				playerReady(playerID, isReady);
			} else if (topic.equals("start")) {
				// Game started
				started();
			} else if (topic.equals("stop")) {
				// Game stopped
				stopped();
			} else if (topic.equals("pause")) {
				// Game paused
				paused();
			} else if (topic.equals("found")) {
				// Player found their object
				int playerNumber = ((Number) message.get("playerNumber")).intValue();
				handler.playerFoundObject(playerID, playerNumber);
			} else if (topic.equals("heartbeat")) {
				// Heartbeat
				heartbeatReceived(playerID);
			}
		}

	}

	/**
	 * Handles team-specific messages.
	 */
	private class TeamConsumer extends Consumer {

		public TeamConsumer(Channel channel) throws IOException {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message, BasicProperties props) throws IOException {
			// TODO Auto-generated method stub

		}

	}

}
