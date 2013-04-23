package peno.htttp;

import java.util.HashMap;
import java.util.Map;

public class PlayerDetails {

	private final String playerID;
	private final PlayerType type;
	private final double width;
	private final double height;

	public PlayerDetails(String playerID, PlayerType type, double width, double height) {
		this.playerID = playerID;
		this.type = type;
		this.width = width;
		this.height = height;
	}

	public final String getPlayerID() {
		return playerID;
	}

	public final PlayerType getType() {
		return type;
	}

	public final double getWidth() {
		return width;
	}

	public final double getHeight() {
		return height;
	}

	static PlayerDetails read(Map<String, Object> in) {
		if (in == null)
			return null;

		String playerID = (String) in.get(Constants.PLAYER_ID);
		PlayerType type = PlayerType.valueOf((String) in.get(Constants.PLAYER_TYPE));
		double width = ((Number) in.get(Constants.PLAYER_WIDTH)).doubleValue();
		double height = ((Number) in.get(Constants.PLAYER_HEIGHT)).doubleValue();

		return new PlayerDetails(playerID, type, width, height);
	}

	Map<String, Object> write() {
		Map<String, Object> out = new HashMap<String, Object>();
		out.put(Constants.PLAYER_ID, getPlayerID());
		out.put(Constants.PLAYER_TYPE, getType().name());
		out.put(Constants.PLAYER_WIDTH, getWidth());
		out.put(Constants.PLAYER_HEIGHT, getHeight());
		return out;
	}

}
