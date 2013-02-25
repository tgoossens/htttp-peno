package peno.htttp;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.tools.json.JSONWriter;

/**
 * A client for communicating over the HTTTP protocol.
 */
public class Client {

	private final Connection connection;
	private final Channel channel;
	private final Handler handler;

	private final String gameID;
	private final String playerID;

	private boolean isHost;

	public Client(ConnectionFactory connectionFactory, Handler handler,
			String gameID, String playerID) throws IOException {
		this.handler = handler;
		this.gameID = gameID;
		this.playerID = playerID;

		this.connection = connectionFactory.newConnection();
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
			// Create host handler...
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

	private void shutdown() {
		try {
			if (isHost) {
				// Host closes game exchange
				channel.exchangeDelete(getGameID());
			}

			// Close connection
			this.channel.close();
			this.connection.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
