package peno.htttp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import peno.htttp.impl.PlayerRegister;
import peno.htttp.impl.PlayerRoll;
import peno.htttp.impl.PlayerState;
import peno.htttp.impl.RequestProvider;
import peno.htttp.impl.Requester;
import peno.htttp.impl.VoteRequester;

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
	public static final int requestLifetime = 2000;
	public static final int heartbeatFrequency = 2000;
	public static final int heartbeatLifetime = 5000;

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
	 * Team communication
	 */
	private String teamPartner = null;

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

	/*
	 * Seesaw
	 */
	private int seesawLock = 0;

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
			return players.getNbConfirmedPlayers();
		}
	}

	/**
	 * Get the number of players which may end up joining the game.
	 * 
	 * This consists of the number of confirmed players (as they are already in
	 * the game) as well as the number of voted players (as they might succeed
	 * in joining).
	 * 
	 * @return
	 */
	private int getNbVotedPlayers() {
		synchronized (players) {
			return players.getNbVotedPlayers();
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

	private PlayerState confirmPlayer(String clientID, String playerID, boolean isReady) {
		synchronized (players) {
			// Attempt to use voted player
			PlayerState player = players.getVoted(clientID, playerID);
			if (player == null) {
				// Not previously voted for player, create new
				player = new PlayerState(clientID, playerID);
			}
			// Restore from missing
			restorePlayer(player);
			// Confirm and set ready
			players.confirm(player);
			player.setReady(isReady);
			return player;
		}
	}

	private PlayerState votePlayer(String clientID, String playerID) {
		synchronized (players) {
			// Create player
			PlayerState player = new PlayerState(clientID, playerID);
			// Restore from missing
			restorePlayer(player);
			// Vote
			players.vote(player);
			return player;
		}
	}

	private void removePlayer(String clientID, String playerID) {
		synchronized (players) {
			// Remove from confirmed and voted
			players.remove(clientID, playerID);
		}
	}

	private void restorePlayer(PlayerState player) {
		synchronized (players) {
			PlayerState missingPlayer = players.getMissing(player.getPlayerID());
			if (missingPlayer != null) {
				missingPlayer.copyTo(player);
			}
		}
	}

	private boolean isMissingPlayer(String playerID) {
		synchronized (players) {
			return players.isMissing(playerID);
		}
	}

	private Collection<PlayerState> getMissingPlayers() {
		synchronized (players) {
			return players.getMissing();
		}
	}

	private void setMissingPlayer(PlayerState missingPlayer) {
		synchronized (players) {
			if (missingPlayer != null) {
				players.setMissing(missingPlayer);
			}
		}
	}

	private boolean isPlayerConnected(String clientID, String playerID) {
		synchronized (players) {
			// Missing players are disconnected
			if (isMissingPlayer(playerID))
				return false;
			// Confirmed players are connected
			if (hasPlayer(clientID, playerID))
				return true;
			// Voted players are connected
			return players.isVoted(clientID, playerID);
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
		new JoinRequester(callback).request(requestLifetime);
	}

	/**
	 * Check if the given player can join.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	protected boolean canJoin(String clientID, String playerID) {
		switch (getGameState()) {
		case JOINING:
		case STARTING:
		case WAITING:
			// Reject duplicate players
			if (!players.canJoin(clientID, playerID))
				return false;
			// Reject when might become full
			if (getNbVotedPlayers() >= nbPlayers)
				return false;
			return true;
		case PLAYING:
			// Nobody can join while playing
			return false;
		case PAUSED:
			// Only missing players can join
			return isMissingPlayer(playerID);
		default:
		}
		return false;
	}

	private void joined() throws IOException {
		// Setup public queue
		setupPublic();
		// Try to roll
		tryRoll();
		// Trigger handlers for previously found objects
		triggerFoundObjects();
		// Rejoin team
		if (hasTeamNumber()) {
			joinTeam(getTeamNumber());
		}
	}

	private void playerJoining(String clientID, String playerID, BasicProperties props) throws IOException {
		// Retrieve game state before voting
		Map<String, Object> gameState = writeGameState();

		// Check if accepted
		boolean isAccepted = canJoin(clientID, playerID);
		// Vote for player
		votePlayer(clientID, playerID);

		// Create and send reply
		Map<String, Object> reply = newMessage();
		reply.put(Constants.VOTE_RESULT, isAccepted);
		// Add data if accepted
		if (isAccepted) {
			// Report own player state
			PlayerState player = getLocalPlayer();
			reply.put(Constants.CLIENT_ID, player.getClientID());
			reply.put(Constants.IS_READY, player.isReady());
			reply.put(Constants.IS_JOINED, isJoined());
			reply.putAll(player.write());
			// Report game state
			reply.putAll(gameState);
		}
		reply(props, reply);

		// Call handler
		handler.playerJoining(playerID);
	}

	private synchronized void playerJoined(String clientID, String playerID) throws IOException {
		// Confirm player
		confirmPlayer(clientID, playerID, false);
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
			message.put(Constants.CLIENT_ID, getClientID());
			message.put(Constants.DISCONNECT_REASON, reason.name());
			publish(Constants.DISCONNECT, message);
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

	private synchronized void playerDisconnected(String clientID, String playerID, DisconnectReason reason) {
		// Ignore if player has already disconnected
		// Can occur when receiving multiple heart beat timeout disconnects
		if (!isPlayerConnected(clientID, playerID))
			return;

		// Call handler
		handler.playerDisconnected(playerID, reason);

		switch (getGameState()) {
		case JOINING:
			// Remove player
			removePlayer(clientID, playerID);
			break;
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
				setMissingPlayer(getPlayer(playerID));
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
			message.put(Constants.IS_READY, isReady);
			publish(Constants.READY, message);
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
		publish(Constants.START, null);
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
		publish(Constants.STOP, null);
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
	 * Get the local player's number.
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
		message.put(Constants.ROLL_NUMBER, roll);
		publish(Constants.ROLL, message);
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
			handler.gameRolled(getPlayerNumber(), getObjectNumber());
		}
	}

	private void sortPlayerRolls() {
		// Sort rolls
		PlayerRoll[] rolls = playerRolls.values().toArray(new PlayerRoll[nbPlayers]);
		Arrays.sort(rolls);

		// Generate player numbers
		playerNumbers.clear();
		for (int i = 0; i < nbPlayers; ++i) {
			playerNumbers.put(rolls[i].getPlayerID(), i + 1);
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
	 * Position
	 */

	/**
	 * Publish the updated position of the local player.
	 * 
	 * <p>
	 * The standard coordinate system has the X-axis running from left to right
	 * and the Y-axis from bottom to top. The angle of orientation is measured
	 * counterclockwise from the positive X-axis to the positive Y-axis.
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
		message.put(Constants.PLAYER_NUMBER, getPlayerNumber());
		message.put(Constants.UPDATE_X, x);
		message.put(Constants.UPDATE_Y, y);
		message.put(Constants.UPDATE_ANGLE, angle);
		message.put(Constants.UPDATE_FOUND_OBJECT, hasFoundObject());
		publish(Constants.UPDATE, message);
	}

	/*
	 * Seesaw
	 */

	/**
	 * Check if the local player holds a lock on any seesaw.
	 */
	public boolean hasLockOnSeesaw() {
		return seesawLock != 0;
	}

	/**
	 * Check if the local player holds a lock on the given seesaw.
	 * 
	 * @param barcode
	 *            The barcode at the player's side of the seesaw.
	 */
	public boolean hasLockOnSeesaw(int barcode) {
		return seesawLock == barcode;
	}

	/**
	 * Unlock a locked seesaw.
	 * 
	 * @throws IllegalStateException
	 *             If the player has no lock on a seesaw.
	 * @throws IOException
	 */
	public void unlockSeesaw() throws IllegalStateException, IOException {
		if (!hasLockOnSeesaw()) {
			throw new IllegalStateException("Cannot unlock seesaw when not holding any lock.");
		}

		// Remove lock
		int unlockedBarcode = seesawLock;
		seesawLock = 0;

		// Publish unlock
		Map<String, Object> message = newMessage();
		message.put(Constants.PLAYER_NUMBER, getPlayerNumber());
		message.put(Constants.SEESAW_BARCODE, unlockedBarcode);
		publish(Constants.SEESAW_UNLOCK, message);
	}

	/**
	 * Lock a seesaw.
	 * 
	 * <p>
	 * A lock should be requested after the barcode in front of the seesaw has
	 * been read and just before the player wants to traverse the seesaw.
	 * </p>
	 * 
	 * <p>
	 * The player is responsible for checking that:
	 * <ul>
	 * <li>the seesaw is open</li>
	 * <li>no players are on the seesaw</li>
	 * </ul>
	 * </p>
	 * 
	 * <p>
	 * The player must provide the barcode that has been read in front of the
	 * seesaw. This is used by listening spectators to identify the seesaw and
	 * the direction in which the seesaw will flip.
	 * </p>
	 * 
	 * @param barcode
	 *            The barcode at the player's side of the seesaw.
	 * @throws IllegalStateException
	 *             If not playing, or if still holding a lock on a different
	 *             seesaw.
	 * @throws IOException
	 */
	public void lockSeesaw(final int barcode) throws IllegalStateException, IOException {
		if (!isPlaying()) {
			throw new IllegalStateException("Cannot lock seesaw when not playing.");
		}

		// Ignore if lock already acquired
		if (hasLockOnSeesaw(barcode)) {
			return;
		}

		// Fail if player still holds a lock on another seesaw
		if (hasLockOnSeesaw()) {
			throw new IllegalStateException("Already holding a lock on a different seesaw.");
		}

		// Store lock
		seesawLock = barcode;

		// Publish lock
		final Map<String, Object> message = newMessage();
		message.put(Constants.PLAYER_NUMBER, getPlayerNumber());
		message.put(Constants.SEESAW_BARCODE, barcode);
		publish(Constants.SEESAW_LOCK, message);
	}

	/*
	 * Object finding
	 */

	/**
	 * Get the local player's object number to identify its object.
	 * 
	 * @throws IllegalStateException
	 *             If not determined yet.
	 */
	public int getObjectNumber() throws IllegalStateException {
		if (!hasObjectNumber()) {
			throw new IllegalStateException("Object number not determined yet.");
		}
		return getPlayerNumber() - 1;
	}

	/**
	 * Check if the object number has been determined.
	 */
	public boolean hasObjectNumber() {
		return hasPlayerNumber();
	}

	/**
	 * Check whether the local player has found their object.
	 */
	public boolean hasFoundObject() {
		return getLocalPlayer().hasFoundObject();
	}

	private void triggerFoundObjects() throws IOException {
		if (!hasPlayerNumber())
			return;

		// Trigger handlers for currently connected players
		// This includes the local player
		for (PlayerState player : players.getConfirmed()) {
			if (player.hasFoundObject()) {
				String playerID = player.getPlayerID();
				handler.playerFoundObject(playerID, playerNumbers.get(playerID));
			}
		}
	}

	/**
	 * Publish a notification indicating that the local player has found their
	 * object.
	 * 
	 * @throws IllegalStateException
	 *             If not playing or if already found.
	 * @throws IOException
	 */
	public void foundObject() throws IllegalStateException, IOException {
		if (!isPlaying()) {
			throw new IllegalStateException("Cannot find object when not playing.");
		}
		if (hasFoundObject()) {
			throw new IllegalStateException("Object already found.");
		}

		// Mark own object as found
		getLocalPlayer().setFoundObject(true);

		// Publish
		Map<String, Object> message = newMessage();
		message.put(Constants.PLAYER_NUMBER, getPlayerNumber());
		publish(Constants.FOUND_OBJECT, message);
	}

	private void playerFoundObject(String playerID) {
		// Mark object as found
		getPlayer(playerID).setFoundObject(true);

		// Call handler
		int playerNumber = playerNumbers.get(playerID);
		handler.playerFoundObject(playerID, playerNumber);
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
		publish(Constants.HEARTBEAT, null);
	}

	private void heartbeatCheck() throws IOException {
		final long limit = System.currentTimeMillis() - heartbeatLifetime;
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
		// Handle locally
		playerDisconnected(player.getClientID(), player.getPlayerID(), DisconnectReason.TIMEOUT);

		// Publish leave for player
		Map<String, Object> message = newMessage();
		message.put(Constants.PLAYER_ID, player.getPlayerID());
		message.put(Constants.CLIENT_ID, player.getClientID());
		message.put(Constants.DISCONNECT_REASON, DisconnectReason.TIMEOUT.name());
		publish(Constants.DISCONNECT, message);
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
	 * Get the local player's team number.
	 * 
	 * @throws IllegalStateException
	 *             If not in any team yet.
	 */
	public int getTeamNumber() {
		if (!hasTeamNumber()) {
			throw new IllegalStateException("Not in any team yet.");
		}

		return getLocalPlayer().getTeamNumber();
	}

	/**
	 * Check if the local player is in a team yet.
	 */
	public boolean hasTeamNumber() {
		return getLocalPlayer().getTeamNumber() >= 0;
	}

	/**
	 * Get the player identifier of the team partner.
	 * 
	 * @throws IllegalStateException
	 *             If not in any team yet, or if partner still unknown.
	 */
	public String getTeamPartner() {
		if (!hasTeamNumber()) {
			throw new IllegalStateException("Not in any team yet.");
		}
		if (!hasTeamPartner()) {
			throw new IllegalStateException("Partner still unknown.");
		}
		return teamPartner;
	}

	/**
	 * Check if the team partner is known yet.
	 */
	public boolean hasTeamPartner() {
		return hasTeamNumber() && teamPartner != null;
	}

	/**
	 * Join the given team.
	 * 
	 * @param teamNumber
	 *            The team number.
	 * @throws IllegalStateException
	 *             If not playing or already in a team.
	 * @throws IOException
	 */
	public void joinTeam(int teamNumber) throws IllegalStateException, IOException {
		if (!isPlaying()) {
			throw new IllegalStateException("Cannot join team when not playing.");
		}
		if (hasTeamNumber()) {
			throw new IllegalStateException("Already joined a team.");
		}

		// Set team number
		getLocalPlayer().setTeamNumber(teamNumber);

		// Start listening
		setupTeam(getTeamNumber());

		/*
		 * TODO Publish team number for others to store it in case this player
		 * loses their connection and rejoins.
		 */

		// Ping for partner
		new TeamPingRequester(channel, requestProvider).request(requestLifetime);
	}

	/**
	 * Called when a ping was received.
	 * 
	 * @param playerID
	 * @throws IOException
	 */
	private void teamPingReceived(String playerID, BasicProperties props) throws IOException {
		// Store team partner
		this.teamPartner = playerID;

		// Reply with a pong
		reply(props, newMessage());

		// Start communicating
		handler.teamConnected(playerID);
	}

	/**
	 * Called when a pong was received.
	 * 
	 * @param playerID
	 */
	private void teamPongReceived(String playerID) {
		// Store team partner
		this.teamPartner = playerID;

		// Start communicating
		handler.teamConnected(playerID);
	}

	/**
	 * Called when the ping expired.
	 */
	private void teamPingNoResponse() {
		// Already listening
	}

	/**
	 * Send maze tiles to the team partner.
	 * 
	 * @param tiles
	 *            The tiles to send.
	 * @throws IllegalStateException
	 *             If not in any team yet, or if partner still unknown.
	 * @throws IOException
	 */
	public void sendTiles(Collection<Tile> tiles) throws IOException {
		if (!hasTeamNumber()) {
			throw new IllegalStateException("Not in any team yet.");
		}
		if (!hasTeamPartner()) {
			throw new IllegalStateException("Partner still unknown.");
		}

		// Build raw tiles list
		List<List<Object>> rawTiles = new ArrayList<List<Object>>(tiles.size());
		for (Tile tile : tiles) {
			rawTiles.add(tile.write());
		}

		// Send tiles
		Map<String, Object> message = newMessage();
		message.put(Constants.TILES, rawTiles);
		publish(toTeamTopic(Constants.TEAM_TILE), message);
	}

	/**
	 * Send maze tiles to the team partner.
	 * 
	 * @param tiles
	 *            The tiles to send.
	 * @throws IllegalStateException
	 *             If not in any team yet, or if partner still unknown.
	 * @throws IOException
	 * @see {@link #sendTiles(Collection)}
	 */
	public void sendTiles(Tile... tiles) throws IOException {
		sendTiles(Arrays.asList(tiles));
	}

	/**
	 * Called when tiles have been received.
	 * 
	 * @param message
	 */
	private void teamTilesReceived(Map<String, Object> message) {
		@SuppressWarnings("unchecked")
		List<List<Object>> rawTiles = (List<List<Object>>) message.get(Constants.TILES);
		// Build tile objects
		List<Tile> tiles = new ArrayList<Tile>(rawTiles.size());
		for (List<Object> rawTile : rawTiles) {
			tiles.add(Tile.read(rawTile));
		}
		// Call handler
		handler.teamTilesReceived(tiles);
	}

	protected String toTeamTopic(String topic) {
		if (!hasTeamNumber()) {
			throw new IllegalStateException("Not in any team yet.");
		}
		return "team." + getTeamNumber() + "." + topic;
	}

	protected String fromTeamTopic(String teamTopic) {
		if (!hasTeamNumber()) {
			throw new IllegalStateException("Not in any team yet.");
		}
		return teamTopic.substring(toTeamTopic("").length());
	}

	/*
	 * Winning
	 */

	/**
	 * Win the game.
	 * 
	 * @throws IllegalStateException
	 *             If not playing, if not in any team yet or if partner still
	 *             unknown.
	 * @throws IOException
	 */
	public void win() throws IllegalStateException, IOException {
		if (!isPlaying()) {
			throw new IllegalStateException("Cannot win when not playing.");
		}
		if (!hasTeamNumber()) {
			throw new IllegalStateException("Cannot win when not in a team yet.");
		}
		if (!hasTeamPartner()) {
			throw new IllegalStateException("Cannot win when partner still unknown.");
		}

		// Publish win
		Map<String, Object> message = newMessage();
		message.put(Constants.TEAM_NUMBER, getTeamNumber());
		publish(Constants.WIN, message);

		// Stop the game
		stop();
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

	private void setupTeam(int teamNumber) throws IOException {
		teamConsumer = new TeamConsumer(channel);
		teamConsumer.bind(getGameID(), toTeamTopic("*"));
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
		// Reset local player
		getLocalPlayer().reset();
		// Only retain local player
		synchronized (players) {
			players.clear();
			players.confirm(getLocalPlayer());
		}
	}

	private void readGameState(Map<String, Object> message) {
		// Game state (if valid)
		String gameState = (String) message.get(Constants.GAME_STATE);
		if (gameState != null) {
			setGameState(GameState.valueOf(gameState));
		}

		// Player numbers (if valid)
		@SuppressWarnings("unchecked")
		Map<String, Integer> playerNumbers = (Map<String, Integer>) message.get(Constants.PLAYER_NUMBERS);
		if (playerNumbers != null) {
			replacePlayerNumbers(playerNumbers);
		}

		// Missing players (if valid)
		@SuppressWarnings("unchecked")
		Iterable<Map<String, Object>> missingPlayers = (Iterable<Map<String, Object>>) message
				.get(Constants.MISSING_PLAYERS);
		if (missingPlayers != null) {
			for (Map<String, Object> playerData : missingPlayers) {
				// Create missing player
				String playerID = (String) playerData.get(Constants.PLAYER_ID);
				PlayerState missingPlayer = new PlayerState(null, playerID);
				missingPlayer.read(playerData);
				// Store as missing player
				setMissingPlayer(missingPlayer);
			}
			// Try to restore from missing local player
			restorePlayer(getLocalPlayer());
		}
	}

	private Map<String, Object> writeGameState() {
		Map<String, Object> state = new HashMap<String, Object>();
		// Game state
		if (isJoined()) {
			state.put(Constants.GAME_STATE, getGameState().name());
		}
		// Player numbers
		if (hasPlayerNumber()) {
			state.put(Constants.PLAYER_NUMBERS, playerNumbers);
		}
		// Missing players
		Collection<PlayerState> missingPlayers = getMissingPlayers();
		if (!missingPlayers.isEmpty()) {
			List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(missingPlayers.size());
			for (PlayerState missingPlayer : missingPlayers) {
				out.add(missingPlayer.write());
			}
			state.put(Constants.MISSING_PLAYERS, out);
		}
		return state;
	}

	/*
	 * Helpers
	 */

	protected void publish(String routingKey, Map<String, Object> message, BasicProperties props) throws IOException {
		channel.basicPublish(getGameID(), routingKey, props, serializeToJSON(message));
	}

	protected void publish(String routingKey, Map<String, Object> message) throws IOException {
		publish(routingKey, message, defaultProps().build());
	}

	protected void reply(BasicProperties requestProps, Map<String, Object> message) throws IOException {
		BasicProperties props = defaultProps().correlationId(requestProps.getCorrelationId()).build();
		channel.basicPublish("", requestProps.getReplyTo(), props, serializeToJSON(message));
	}

	private AMQP.BasicProperties.Builder defaultProps() {
		return new AMQP.BasicProperties.Builder().timestamp(new Date()).contentType("text/plain").deliveryMode(1);
	}

	protected Map<String, Object> newMessage() {
		Map<String, Object> message = new HashMap<String, Object>();

		// Add player ID to message
		message.put(Constants.PLAYER_ID, getPlayerID());

		return message;
	}

	protected byte[] serializeToJSON(Map<String, Object> message) {
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
	private class JoinRequester extends VoteRequester {

		private final Callback<Void> callback;

		public JoinRequester(Callback<Void> callback) throws IOException {
			super(channel, requestProvider);
			this.callback = callback;
		}

		public void request(int timeout) throws IOException {
			// Publish join with own player info
			Map<String, Object> message = createMessage();
			request(getGameID(), Constants.JOIN, serializeToJSON(message), timeout);
		}

		@Override
		protected int getRequiredVotes() {
			// Short-circuit when game is full and all other players accepted
			return nbPlayers - 1;
		}

		@Override
		protected void onAccepted(Map<String, Object> message) {
			// Accepted by peer
			String clientID = (String) message.get(Constants.CLIENT_ID);
			String playerID = (String) message.get(Constants.PLAYER_ID);
			boolean isReady = (Boolean) message.get(Constants.IS_READY);
			boolean isJoined = (Boolean) message.get(Constants.IS_JOINED);

			// Store player
			PlayerState player;
			if (isJoined) {
				player = confirmPlayer(clientID, playerID, isReady);
			} else {
				player = votePlayer(clientID, playerID);
			}
			// Read player state
			player.read(message);
			// Read game state
			readGameState(message);
		}

		@Override
		protected void onRejected(Map<String, Object> message) {
		}

		@Override
		protected void onSuccess() {
			// No response, first player in game
			// Set game state if not set yet
			if (!isJoined()) {
				setGameState(GameState.WAITING);
			}
			try {
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

		@Override
		protected void onFailure() {
			try {
				// Disconnect
				disconnect(DisconnectReason.REJECT);
			} catch (IOException e) {
				callback.onFailure(e);
			}

			// Report failure
			callback.onFailure(new Exception("Join request rejected"));
		}

		@Override
		protected void handleTimeout() {
			// Join was successful
			success();
		}

		private Map<String, Object> createMessage() {
			PlayerState player = getLocalPlayer();
			Map<String, Object> message = newMessage();
			message.put(Constants.CLIENT_ID, player.getClientID());
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
			String clientID = (String) message.get(Constants.CLIENT_ID);
			String playerID = (String) message.get(Constants.PLAYER_ID);

			// Ignore local messages
			if (getClientID().equals(clientID))
				return;

			if (topic.equals(Constants.JOIN)) {
				// Player joining
				playerJoining(clientID, playerID, props);
			} else if (topic.equals(Constants.JOINED)) {
				// Player joined
				playerJoined(clientID, playerID);
			} else if (topic.equals(Constants.DISCONNECT)) {
				// Player disconnected
				DisconnectReason reason = DisconnectReason.valueOf((String) message.get(Constants.DISCONNECT_REASON));
				playerDisconnected(clientID, playerID, reason);
			} else if (topic.equals(Constants.ROLL)) {
				// Player rolled their number
				int roll = (Integer) message.get(Constants.ROLL_NUMBER);
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
			String playerID = (String) message.get(Constants.PLAYER_ID);
			if (topic.equals(Constants.READY)) {
				// Player ready
				boolean isReady = (Boolean) message.get(Constants.IS_READY);
				playerReady(playerID, isReady);
			} else if (topic.equals(Constants.START)) {
				// Game started
				started();
			} else if (topic.equals(Constants.STOP)) {
				// Game stopped
				stopped();
			} else if (topic.equals(Constants.PAUSE)) {
				// Game paused
				paused();
			} else if (topic.equals(Constants.FOUND_OBJECT)) {
				// Player found their object
				playerFoundObject(playerID);
			} else if (topic.equals(Constants.HEARTBEAT)) {
				// Heartbeat
				heartbeatReceived(playerID);
			} else if (topic.equals(Constants.UPDATE)) {
				if (hasTeamPartner() && getTeamPartner().equals(playerID)) {
					// Partner updated their position
					double x = ((Number) message.get(Constants.UPDATE_X)).doubleValue();
					double y = ((Number) message.get(Constants.UPDATE_Y)).doubleValue();
					double angle = ((Number) message.get(Constants.UPDATE_ANGLE)).doubleValue();
					handler.teamPosition(x, y, angle);
				}
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
			String playerID = (String) message.get(Constants.PLAYER_ID);
			topic = fromTeamTopic(topic);

			// Ignore local messages
			if (getPlayerID().equals(playerID))
				return;

			if (topic.equals(Constants.TEAM_PING)) {
				// Partner connected
				teamPingReceived(playerID, props);
			} else if (topic.equals(Constants.TEAM_TILE)) {
				// Tiles received
				teamTilesReceived(message);
			}

		}

	}

	private class TeamPingRequester extends Requester {

		public TeamPingRequester(Channel channel, RequestProvider provider) throws IOException {
			super(channel, provider);
		}

		public void request(int timeout) throws IOException {
			// Publish ping
			Map<String, Object> message = newMessage();
			request(getGameID(), toTeamTopic(Constants.TEAM_PING), serializeToJSON(message), timeout);
		}

		@Override
		protected void handleResponse(Map<String, Object> message, BasicProperties props) {
			// Partner responded
			String playerID = (String) message.get(Constants.PLAYER_ID);
			teamPongReceived(playerID);
		}

		@Override
		protected void handleTimeout() {
			// Partner did not respond
			teamPingNoResponse();
		}

	}

}
