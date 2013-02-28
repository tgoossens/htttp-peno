package peno.htttp.impl;

public class PlayerInfo {

	private final String playerID;
	private PlayerState state;

	public PlayerInfo(String playerID, PlayerState state) {
		this.playerID = playerID;
		setState(state);
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
