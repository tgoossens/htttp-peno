package peno.htttp.impl;

public class PlayerInfo {

	private final String clientID;
	private final String playerID;
	private boolean isReady;

	public PlayerInfo(String clientID, String playerID, boolean isReady) {
		this.clientID = clientID;
		this.playerID = playerID;
		setReady(isReady);
	}

	public String getClientID() {
		return clientID;
	}

	public String getPlayerID() {
		return playerID;
	}

	public boolean isReady() {
		return isReady;
	}

	public void setReady(boolean isReady) {
		this.isReady = isReady;
	}

}
