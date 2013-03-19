package peno.htttp.impl;

public class PlayerRoll implements Comparable<PlayerRoll> {

	private final String playerID;

	private final int roll;

	public PlayerRoll(String playerID, int roll) {
		this.playerID = playerID;
		this.roll = roll;
	}

	public String getPlayerID() {
		return playerID;
	}

	public int getRoll() {
		return roll;
	}

	@Override
	public int compareTo(PlayerRoll other) {
		return ((Integer) getRoll()).compareTo(other.getRoll());
	}

}
