package peno.htttp.impl;

import java.io.IOException;
import java.util.Map;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.tools.json.JSONReader;

public abstract class Consumer extends DefaultConsumer {

	public Consumer(Channel channel) {
		super(channel);
	}

	@Override
	public void handleDelivery(String consumerTag, Envelope envelope,
			BasicProperties props, byte[] body) throws IOException {
		String topic = envelope.getRoutingKey();
		Map<String, Object> message = parseMessage(body);

		handleMessage(topic, message, props);
	}

	public abstract void handleMessage(String topic,
			Map<String, Object> message, BasicProperties props)
			throws IOException;

	private static JSONReader jsonReader = new JSONReader();

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseMessage(byte[] body) {
		return (Map<String, Object>) jsonReader.read(new String(body));
	}

}
