package peno.htttp;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import peno.htttp.impl.PlayerInfo;
import peno.htttp.impl.PlayerState;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.tools.json.JSONReader;
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
	private String publicQueue;
	private String teamQueue;

	/*
	 * Identifiers
	 */
	private final String gameID;
	private final String playerID;

	/*
	 * Game state
	 */
	private GameState gameState = GameState.DISCONNECTED;
	private final Map<String, PlayerInfo> players = new HashMap<String, PlayerInfo>();

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
		this.playerID = playerID;

		this.channel = connection.createChannel();
		setup();
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
		return playerID;
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

	/**
	 * Check whether the game is currently paused.
	 */
	public boolean isPaused() {
		return getGameState() == GameState.PAUSED;
	}

	/*
	 * Player tracking
	 */

	/**
	 * Get the number of players currently in to the game.
	 */
	public int getNbPlayers() {
		synchronized (players) {
			return players.size();
		}
	}

	/**
	 * Check whether the game is currently full.
	 */
	public boolean isFull() {
		return getNbPlayers() >= nbPlayers;
	}

	private boolean hasPlayer(String playerID) {
		return players.containsKey(playerID);
	}

	private PlayerInfo getPlayer(String playerID) {
		synchronized (players) {
			return players.get(playerID);
		}
	}

	private PlayerInfo getLocalPlayer() {
		String playerID = getPlayerID();
		if (!hasPlayer(playerID)) {
			setPlayer(playerID, PlayerState.NOT_READY);
		}
		return getPlayer(playerID);
	}

	private void setPlayer(String playerID, PlayerState state) {
		synchronized (players) {
			if (hasPlayer(playerID)) {
				players.get(playerID).setState(state);
			} else {
				players.put(playerID, new PlayerInfo(playerID, state));
			}
		}
	}

	private void setPlayer(String playerID, boolean isReady) {
		setPlayer(playerID, isReady ? PlayerState.READY : PlayerState.NOT_READY);
	}

	private void removePlayer(String playerID) {
		synchronized (players) {
			players.remove(playerID);
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

		// Request to join
		new JoinRequester(callback).request(joinExpiration);
	}

	protected boolean canJoin(String playerID) {
		switch (getGameState()) {
		case PLAYING:
			// Nobody can join while playing
			return false;
		case PAUSED:
			// Only missing players can join
			return hasPlayer(playerID);
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

	protected void playerJoined(String playerID, boolean isReady) {
		// Store player
		setPlayer(playerID, isReady);
		// Report
		handler.playerJoined(playerID);
	}

	/**
	 * Leave this game.
	 * 
	 * @throws IOException
	 */
	public void leave() throws IOException {
		// Reset game
		resetGame();
		// Notify leaving
		publish("leave", null);
		// Shut down
		shutdownPublic();
		shutdownTeam();
	}

	protected void playerLeft(String playerID) {
		// Report
		handler.playerLeft(playerID);

		switch (getGameState()) {
		case WAITING:
			// Simply remove player
			removePlayer(playerID);
			break;
		case PLAYING:
		case PAUSED:
			if (hasPlayer(playerID)) {
				// Player went missing
				getPlayer(playerID).setState(PlayerState.DISCONNECTED);
				// Pause
				try {
					pause();
				} catch (IllegalStateException | IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			break;
		default:
			break;
		}
	}

	/*
	 * Starting/stopping
	 */

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
			Map<String, Object> message = new HashMap<String, Object>();
			message.put("isReady", playerInfo.isReady());
			publish("ready", message);
		}
	}

	// TODO Start automatically?
	protected void start(Callback<Void> callback) {
		if (!isConnected()) {
			throw new IllegalStateException("Not connected to game.");
		}

		// TODO Check if can start
		// started();
	}

	protected void started() {
		// Update game state
		setGameState(GameState.PLAYING);
		// Call handler
		handler.gameStarted();
	}

	/**
	 * Stop the game completely.
	 * 
	 * @param callback
	 *            A callback which receives the result of this request.
	 */
	public void stop(Callback<Void> callback) {
		if (!isConnected()) {
			throw new IllegalStateException("Not connected to game.");
		}

		if (getGameState() == GameState.WAITING) {
			// Game already stopped
			callback.onSuccess(null);
		}

		// TODO Send stop message
		// stopped();
	}

	protected void stopped() {
		// Update game state
		setGameState(GameState.WAITING);
		// Call handler
		handler.gameStopped();
	}

	protected void pause() throws IOException, IllegalStateException {
		if (!isConnected()) {
			throw new IllegalStateException("Not connected to game.");
		}

		// Already paused
		if (isPaused())
			return;

		publish("pause", null);
		paused();
	}

	protected void paused() {
		// Update game state
		setGameState(GameState.PAUSED);
		// Call handler
		handler.gamePaused();
	}

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
	 * @throws IOException
	 */
	public void updatePosition(double x, double y, double angle)
			throws IOException {
		Map<String, Object> message = new HashMap<String, Object>();
		message.put("x", x);
		message.put("y", y);
		message.put("angle", angle);
		publish("position", message);
		// TODO Wait for result
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

	private void setupPublic() throws IOException {
		// Declare and bind
		publicQueue = channel.queueDeclare().getQueue();
		channel.queueBind(publicQueue, getGameID(), "*");

		// Attach consumers
		channel.basicConsume(publicQueue, new JoinLeaveHandler(channel));
	}

	private void shutdownPublic() throws IOException {
		// Delete queue (also cancels attached consumers)
		if (publicQueue != null) {
			channel.queueDelete(publicQueue);
			publicQueue = null;
		}
	}

	private void setupTeam(int teamId) throws IOException {
		// Declare and bind
		teamQueue = channel.queueDeclare().getQueue();
		channel.queueBind(teamQueue, getGameID(), "team." + teamId + ".*");

		// Attach consumers
	}

	private void shutdownTeam() throws IOException {
		// Delete queue (also cancels attached consumers)
		if (teamQueue != null) {
			channel.queueDelete(teamQueue);
			teamQueue = null;
		}
	}

	/**
	 * Shutdown this client.
	 * 
	 * <p>
	 * Leaves the game and then closes the opened channel.
	 * </p>
	 */
	public void shutdown() {
		try {
			// Leave the game
			leave();
			// Close channel
			this.channel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void resetGame() {
		// Reset game state
		setGameState(GameState.DISCONNECTED);

		// Only retain local player
		synchronized (players) {
			PlayerInfo localPlayer = getLocalPlayer();
			players.clear();
			players.put(getPlayerID(), localPlayer);
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

	protected void consume(String queue, Consumer consumer, boolean autoAck)
			throws IOException {
		channel.basicConsume(queue, autoAck, "", true, false, null, consumer);
	}

	protected void consume(String queue, Consumer consumer) throws IOException {
		consume(queue, consumer, true);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseMessage(byte[] body) {
		return (Map<String, Object>) new JSONReader().read(new String(body));
	}

	private byte[] prepareMessage(Map<String, Object> message) {
		// Default to empty message
		if (message == null) {
			message = new HashMap<String, Object>();
		}

		// Add player ID to message
		message.put("playerID", getPlayerID());

		// Serialize map as JSON object
		return new JSONWriter().write(message).getBytes();
	}

	/**
	 * Requests a join and handles the responses.
	 */
	private class JoinRequester extends DefaultConsumer {

		private final Callback<Void> callback;
		private String replyQueue;
		private String requestId;
		private volatile boolean isDone = false;

		public JoinRequester(Callback<Void> callback) {
			super(channel);
			this.callback = callback;
		}

		public void request(int timeout) throws IOException {
			// Declare reply consumer
			replyQueue = channel.queueDeclare().getQueue();
			consume(replyQueue, this);

			// Publish "join" with local player info
			PlayerInfo playerInfo = getLocalPlayer();
			Map<String, Object> message = new HashMap<String, Object>();
			message.put("isReady", playerInfo.isReady());

			// Create request
			requestId = UUID.randomUUID().toString();
			AMQP.BasicProperties props = defaultProps()
					.expiration(timeout + "").correlationId(requestId)
					.replyTo(replyQueue).build();

			isDone = false;
			publish("join", message, props);

			// Report success after timeout
			Executors.newSingleThreadScheduledExecutor().schedule(
					new Runnable() {
						@Override
						public void run() {
							if (!isDone) {
								success();
							}
						}
					}, timeout, TimeUnit.MILLISECONDS);
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope,
				BasicProperties props, byte[] body) throws IOException {
			Map<String, Object> message = parseMessage(body);

			if (!isDone && props.getCorrelationId().equals(requestId)) {
				boolean result = (Boolean) message.get("result");
				if (result) {
					// Accepted by peer
					String playerID = (String) message.get("playerID");
					Boolean isReady = (Boolean) message.get("isReady");
					GameState gameState = GameState.valueOf((String) message
							.get("gameState"));
					// Store state
					setPlayer(playerID, isReady);
					setGameState(gameState);
					if (isFull()) {
						// All players registered
						success();
					}
				} else {
					// Rejected by peer
					failure();
				}
			}
		}

		private void success() {
			try {
				// Mark as done
				isDone = true;
				// Setup
				joined();
				// Report success
				callback.onSuccess(null);
			} catch (IOException e) {
				callback.onFailure(e);
			}
		}

		private void failure() {
			try {
				// Mark as done
				isDone = true;
				// Stop listening
				channel.queueDelete(replyQueue);
				// Report failure
				callback.onFailure(new Exception("Request to join rejected"));
				// Leave
				leave();
			} catch (IOException e) {
				callback.onFailure(e);
			}
		}

	}

	/**
	 * Handles join requests
	 */
	private class JoinLeaveHandler extends DefaultConsumer {

		public JoinLeaveHandler(Channel channel) {
			super(channel);
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope,
				BasicProperties props, byte[] body) throws IOException {
			String topic = envelope.getRoutingKey();
			Map<String, Object> message = parseMessage(body);

			if (topic.equals("join")) {
				// Read player info
				String playerID = (String) message.get("playerID");
				boolean isReady = (Boolean) message.get("isReady");

				// Prepare reply
				PlayerInfo playerInfo = getLocalPlayer();
				Map<String, Object> reply = new HashMap<String, Object>();
				reply.put("playerID", playerInfo.getPlayerID());

				boolean isAccepted = false;
				if (canJoin(playerID)) {
					// Accept
					isAccepted = true;
					playerJoined(playerID, isReady);
					// Report state
					reply.put("isReady", playerInfo.isReady());
					reply.put("gameState", getGameState().name());
				} else {
					// Reject
				}
				reply.put("result", isAccepted);

				// Send reply
				reply(props, reply);
			} else if (topic.equals("leave")) {
				// Read player info
				String playerID = (String) message.get("playerID");
				// Handle player left
				playerLeft(playerID);
			}
		}

	}

}
