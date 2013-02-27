package peno.htttp.impl;

public class PlayerInfo {

	private final String playerID;
	private volatile boolean isReady;

	public PlayerInfo(String playerID, boolean isReady) {
		this.playerID = playerID;
		this.isReady = isReady;
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
