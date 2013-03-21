package peno.htttp.impl;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import peno.htttp.Constants;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;

public abstract class VoteRequester extends Requester {

	private final AtomicInteger votes = new AtomicInteger();
	private volatile boolean isDone;

	public VoteRequester(Channel channel, RequestProvider provider) throws IOException {
		super(channel, provider);
	}

	@Override
	protected void request(String exchange, String topic, byte[] message, int timeout) throws IOException {
		// Mark as not done
		isDone = false;
		// Request
		super.request(exchange, topic, message, timeout);
	}

	@Override
	protected void handleResponse(Map<String, Object> message, BasicProperties props) {
		if (isDone())
			return;

		boolean isAccepted = (Boolean) message.get(Constants.VOTE_RESULT);
		if (isAccepted) {
			onAccepted(message);
			if (votes.incrementAndGet() >= getRequiredVotes()) {
				success();
			}
		} else {
			onRejected(message);
			fail();
		}
	}

	public boolean isDone() {
		return isDone;
	}

	private void done() {
		// Mark as done
		isDone = true;
		// Cancel request
		cancelRequest();
	}

	protected abstract int getRequiredVotes();

	protected abstract void onAccepted(Map<String, Object> message);

	protected abstract void onRejected(Map<String, Object> message);

	protected final void success() {
		if (!isDone()) {
			done();
			onSuccess();
		}
	}

	protected abstract void onSuccess();

	protected final void fail() {
		if (!isDone()) {
			done();
			onFailure();
		}
	}

	protected abstract void onFailure();

}
