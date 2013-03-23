package peno.htttp;

import java.io.IOException;
import java.util.Map;

import peno.htttp.impl.Consumer;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * A client for spectating a game over the HTTTP protocol.
 */
public class SpectatorClient {

	/*
	 * Communication
	 */
	private final Connection connection;
	private Channel channel;
	private final SpectatorHandler handler;
	private Consumer consumer;

	/*
	 * Identifiers
	 */
	private final String gameID;

	/**
	 * Create a spectator client.
	 * 
	 * @param connection
	 *            The AMQP connection for communication.
	 * @param handler
	 *            The event handler which listens to this spectator.
	 * @param gameID
	 *            The game identifier.
	 * @throws IOException
	 */
	public SpectatorClient(Connection connection, SpectatorHandler handler, String gameID) throws IOException {
		this.connection = connection;
		this.handler = handler;
		this.gameID = gameID;
	}

	/**
	 * Get the game identifier.
	 */
	public String getGameID() {
		return gameID;
	}

	/**
	 * Start spectating.
	 * 
	 * @throws IOException
	 */
	public void start() throws IOException {
		// Create channel
		channel = connection.createChannel();
		// Declare exchange
		channel.exchangeDeclare(getGameID(), "topic");

		// Setup consumer
		consumer = new SpectatorConsumer(channel);
		consumer.bind(getGameID(), "*");
	}

	/**
	 * Stop spectating.
	 */
	public void stop() {
		// Shut down consumer
		if (consumer != null) {
			consumer.terminate();
		}
		consumer = null;

		// Shut down channel
		try {
			channel.close();
		} catch (IOException e) {
		} catch (ShutdownSignalException e) {
		} finally {
			channel = null;
		}
	}

	/**
	 * Handles spectator broadcasts.
	 */
	private class SpectatorConsumer extends Consumer {

		public SpectatorConsumer(Channel channel) throws IOException {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message, BasicProperties props) throws IOException {
			String playerID = (String) message.get(Constants.PLAYER_ID);
			if (topic.equals(Constants.START)) {
				// Game started
				handler.gameStarted();
			} else if (topic.equals(Constants.STOP)) {
				// Game stopped
				handler.gameStopped();
			} else if (topic.equals(Constants.PAUSE)) {
				// Game paused
				handler.gamePaused();
			} else if (topic.equals(Constants.JOIN)) {
				// Player joining
				handler.playerJoining(playerID);
			} else if (topic.equals(Constants.JOINED)) {
				// Player joined
				handler.playerJoined(playerID);
			} else if (topic.equals(Constants.DISCONNECT)) {
				// Player disconnected
				DisconnectReason reason = DisconnectReason.valueOf((String) message.get(Constants.DISCONNECT_REASON));
				handler.playerDisconnected(playerID, reason);
			} else if (topic.equals(Constants.READY)) {
				// Player ready
				boolean isReady = (Boolean) message.get(Constants.IS_READY);
				handler.playerReady(playerID, isReady);
			} else if (topic.equals(Constants.UPDATE)) {
				// Player updated their state
				int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				double x = ((Number) message.get(Constants.UPDATE_X)).doubleValue();
				double y = ((Number) message.get(Constants.UPDATE_Y)).doubleValue();
				double angle = ((Number) message.get(Constants.UPDATE_ANGLE)).doubleValue();
				boolean foundObject = (Boolean) message.get(Constants.UPDATE_FOUND_OBJECT);
				handler.playerUpdate(playerID, playerNumber, x, y, angle, foundObject);
			} else if (topic.equals(Constants.FOUND_OBJECT)) {
				// Player found their object
				int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				handler.playerFoundObject(playerID, playerNumber);
			} else if (topic.equals(Constants.SEESAW_LOCK)) {
				// Player has locked seesaw
				int playerNumber = ((Number) message.get("playerNumber")).intValue();
				int barcode = ((Number) message.get("barcode")).intValue();
				handler.lockedSeesaw(playerID, playerNumber, barcode);
			} else if (topic.equals(Constants.SEESAW_UNLOCK)) {
				// Player has unlocked seesaw
				int playerNumber = ((Number) message.get("playerNumber")).intValue();
				int barcode = ((Number) message.get("barcode")).intValue();
				handler.unlockedSeesaw(playerID, playerNumber, barcode);
			}

		}

	}

}
