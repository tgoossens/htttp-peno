package peno.htttp;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import peno.htttp.impl.Consumer;
import peno.htttp.impl.NamedThreadFactory;

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
	private final ExecutorService handlerExecutor;
	private static final ThreadFactory handlerFactory = new NamedThreadFactory("HTTTP-SpectatorHandler-%d");

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

		this.handlerExecutor = Executors.newCachedThreadPool(handlerFactory);
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
			// Player messages
			final String playerID = (String) message.get(Constants.PLAYER_ID);
			// Spectator messages
			@SuppressWarnings("unchecked")
			final PlayerDetails player = PlayerDetails
					.read((Map<String, Object>) message.get(Constants.PLAYER_DETAILS));

			if (topic.equals(Constants.START)) {
				// Game started
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.gameStarted();
					}
				});
			} else if (topic.equals(Constants.STOP)) {
				// Game stopped
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.gameStopped();
					}
				});
			} else if (topic.equals(Constants.PAUSE)) {
				// Game paused
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.gamePaused();
					}
				});
			} else if (topic.equals(Constants.JOIN)) {
				// Player joining
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerJoining(playerID);
					}
				});
			} else if (topic.equals(Constants.JOINED)) {
				// Player joined
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerJoined(playerID);
					}
				});
			} else if (topic.equals(Constants.DISCONNECT)) {
				// Player disconnected
				final DisconnectReason reason = DisconnectReason.valueOf((String) message
						.get(Constants.DISCONNECT_REASON));
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerDisconnected(playerID, reason);
					}
				});
			} else if (topic.equals(Constants.READY)) {
				// Player ready
				final boolean isReady = (Boolean) message.get(Constants.IS_READY);
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerReady(playerID, isReady);
					}
				});
			} else if (topic.equals(Constants.ROLLED)) {
				// Player rolled their number
				final int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerRolled(player, playerNumber);
					}
				});
			} else if (topic.equals(Constants.UPDATE)) {
				// Player updated their state
				final int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				final long x = ((Number) message.get(Constants.UPDATE_X)).longValue();
				final long y = ((Number) message.get(Constants.UPDATE_Y)).longValue();
				final double angle = ((Number) message.get(Constants.UPDATE_ANGLE)).doubleValue();
				final boolean foundObject = (Boolean) message.get(Constants.PLAYER_FOUND_OBJECT);
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerUpdate(player, playerNumber, x, y, angle, foundObject);
					}
				});
			} else if (topic.equals(Constants.FOUND_OBJECT)) {
				// Player found their object
				final int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.playerFoundObject(playerID, playerNumber);
					}
				});
			} else if (topic.equals(Constants.WIN)) {
				// Team has won
				final int teamNumber = ((Number) message.get(Constants.TEAM_NUMBER)).intValue();
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.gameWon(teamNumber);
					}
				});
			} else if (topic.equals(Constants.SEESAW_LOCK)) {
				// Player has locked seesaw
				final int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				final int barcode = ((Number) message.get(Constants.SEESAW_BARCODE)).intValue();
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.lockedSeesaw(playerID, playerNumber, barcode);
					}
				});
			} else if (topic.equals(Constants.SEESAW_UNLOCK)) {
				// Player has unlocked seesaw
				final int playerNumber = ((Number) message.get(Constants.PLAYER_NUMBER)).intValue();
				final int barcode = ((Number) message.get(Constants.SEESAW_BARCODE)).intValue();
				handlerExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handler.unlockedSeesaw(playerID, playerNumber, barcode);
					}
				});
			}

		}

	}

}
