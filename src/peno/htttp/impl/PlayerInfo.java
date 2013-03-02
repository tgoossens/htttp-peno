package peno.htttp.impl;

public class PlayerInfo {

	private final String clientID;
	private final String playerID;
	private PlayerState state;

	public PlayerInfo(String clientID, String playerID, PlayerState state) {
		this.clientID = clientID;
		this.playerID = playerID;
		setState(state);
	}

	public String getClientID() {
		return clientID;
	}

	public String getPlayerID() {
		return playerID;
	}

	public PlayerState getState() {
		return state;
	}

	public void setState(PlayerState state) {
		this.state = state;
	}

	public boolean isReady() {
		return state == PlayerState.READY;
	}

}
