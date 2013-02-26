package peno.htttp;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

	private final Channel channel;
	private final Handler handler;

	private final String gameID;
	private final String playerID;

	private boolean isHost;
	private final Set<String> participants = Collections
			.synchronizedSet(new HashSet<String>());

	public Client(Connection connection, Handler handler, String gameID,
			String playerID) throws IOException {
		this.handler = handler;
		this.gameID = gameID;
		this.playerID = playerID;

		this.channel = connection.createChannel();

		setup();
	}

	public String getGameID() {
		return gameID;
	}

	public String getPlayerID() {
		return playerID;
	}

	public void join(Callback<Boolean> callback) throws IOException {
		if (isHost) {
			// Host already in game
			callback.onSuccess(true);
		} else {
			Map<String, Object> message = new HashMap<String, Object>();
			send(message, "join");
			// TODO Wait for result
		}
	}

	public void leave(Callback<Boolean> callback) throws IOException {
		if (isHost) {
			// Host stops game completely
			stop(callback);
		} else {
			//
		}
	}

	private void stop(Callback<Boolean> callback) {
		// TODO Auto-generated method stub

	}

	public void updatePosition(double x, double y, double angle)
			throws IOException {
		Map<String, Object> message = new HashMap<String, Object>();
		message.put("x", x);
		message.put("y", y);
		message.put("angle", angle);
		send(message, "position");
		// TODO Wait for result
	}

	private void setup() throws IOException {
		try {
			channel.exchangeDeclarePassive(getGameID());
			// Exchange already exists
			isHost = false;
		} catch (IOException e) {
			// Exchange does not exist
			channel.exchangeDeclare(getGameID(), "topic");
			isHost = true;
			// Setup host
			setupHost();
		}
	}

	private void setupHost() throws IOException {
		// Queue for host messages
		final String hostQueue = channel.queueDeclare().getQueue();

		// Bind to public topics (single word topics)
		channel.queueBind(hostQueue, getGameID(), "*");

		// Start consuming
		consume(hostQueue, new HostConsumer());
	}

	private void shutdown() {
		try {
			if (isHost) {
				// Host closes game exchange
				channel.exchangeDelete(getGameID());
			}

			// Close channel
			this.channel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void send(Map<String, Object> message, String routingKey)
			throws IOException {
		if (message == null) {
			throw new NullPointerException("Message cannot be null.");
		}

		// Add player ID to message
		message.put("playerID", getPlayerID());

		// Serialize map as JSON object
		String jsonMessage = new JSONWriter().write(message);

		// Publish message
		AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
				.timestamp(new Date()).contentType("text/plain")
				.deliveryMode(1).build();
		channel.basicPublish(getGameID(), routingKey, props,
				jsonMessage.getBytes());
	}

	private void consume(String queue, Consumer consumer) throws IOException {
		channel.basicConsume(queue, false, null, true, false, null, consumer);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseMessage(byte[] body) {
		return (Map<String, Object>) new JSONReader().read(new String(body));
	}

	private class HostConsumer extends DefaultConsumer {

		public HostConsumer() {
			super(channel);
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope,
				BasicProperties properties, byte[] body) throws IOException {
			String topic = envelope.getRoutingKey();
			Map<String, Object> message = parseMessage(body);

			// Delegate to appropriate method
			if (topic.equals("join")) {
				handleJoin(envelope, properties, message);
			}

			// Acknowledge after processing
			channel.basicAck(envelope.getDeliveryTag(), false);
		}

		private void handleJoin(Envelope envelope, BasicProperties properties,
				Map<String, Object> message) {
			Map<String, Object> reply = new HashMap<String, Object>();

			// TODO Check game status

			// Check player count
			if (participants.size() >= 4) {
				reply.put("success", false);
				reply.put("error", "Too many players.");
			}

			String playerID = (String) message.get("playerID");

			// Check if already exists
			if (participants.contains(playerID)) {
				reply.put("success", false);
				reply.put("error", "Player already exists.");
			}

			// Add to participants
			participants.add(playerID);
			reply.put("success", true);

			// TODO Send reply
		}

	}

}
