package peno.htttp.impl;

import java.util.HashMap;
import java.util.Map;

public class PlayerState {

	private final String clientID;
	private final String playerID;

	/*
	 * Persistent
	 */
	private boolean hasFoundObject;
	private int teamNumber;

	/*
	 * Volatile
	 */
	private boolean isReady;
	private long lastHeartbeat;

	public PlayerState(String clientID, String playerID) {
		this.clientID = clientID;
		this.playerID = playerID;
		reset();
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

	public boolean hasFoundObject() {
		return hasFoundObject;
	}

	public void setFoundObject(boolean found) {
		this.hasFoundObject = found;
	}

	public int getTeamNumber() {
		return teamNumber;
	}

	public void setTeamNumber(int teamNumber) {
		this.teamNumber = teamNumber;
	}

	public long getLastHeartbeat() {
		return lastHeartbeat;
	}

	public void setLastHeartbeat(long lastHeartbeat) {
		this.lastHeartbeat = lastHeartbeat;
	}

	public void reset() {
		setReady(false);
		setFoundObject(false);
		setTeamNumber(-1);
		setLastHeartbeat(0);
	}

	public void read(Map<String, Object> in) {
		setFoundObject((Boolean) in.get("hasFoundObject"));
		setTeamNumber(((Number) in.get("teamNumber")).intValue());
	}

	public Map<String, Object> write() {
		Map<String, Object> out = new HashMap<String, Object>();
		out.put("playerID", getPlayerID());
		out.put("hasFoundObject", hasFoundObject());
		out.put("teamNumber", getTeamNumber());
		return out;
	}

	public void copyTo(PlayerState target) {
		target.setFoundObject(hasFoundObject());
		target.setTeamNumber(getTeamNumber());
	}

}
