package peno.htttp;

/**
 * Names of messages and parameters.
 */
public interface Constants {

	/*
	 * General
	 */
	public static final String PLAYER_ID = "playerID";
	public static final String CLIENT_ID = "clientID";
	public static final String PLAYER_NUMBER = "playerNumber";

	/*
	 * Join
	 */
	public static final String JOIN = "join";
	public static final String JOINED = "joined";
	public static final String IS_JOINED = "isJoined";
	public static final String IS_READY = "isReady";

	public static final String GAME_STATE = "gameState";
	public static final String MISSING_PLAYERS = "missingPlayers";
	public static final String PLAYER_NUMBERS = "playerNumbers";

	/*
	 * Disconnect
	 */
	public static final String DISCONNECT = "disconnect";
	public static final String DISCONNECT_REASON = "reason";

	/*
	 * Roll
	 */
	public static final String ROLL = "roll";
	public static final String ROLL_NUMBER = "roll";

	/*
	 * Ready
	 */
	public static final String READY = "ready";

	/*
	 * Start/stop/pause
	 */
	public static final String START = "start";
	public static final String STOP = "stop";
	public static final String PAUSE = "pause";

	/*
	 * Reports
	 */
	public static final String FOUND_OBJECT = "found";
	public static final String HEARTBEAT = "heartbeat";

	/*
	 * Update
	 */
	public static final String UPDATE = "update";
	public static final String UPDATE_X = "x";
	public static final String UPDATE_Y = "y";
	public static final String UPDATE_ANGLE = "angle";
	public static final String UPDATE_FOUND_OBJECT = "foundObject";

	/*
	 * Team
	 */
	public static final String TEAM_PING = "ping";
	public static final String TEAM_TILE = "tile";
	public static final String TEAM_MEET = "meet";
	public static final String TEAM_MATCH = "match";

	public static final String TILES = "tiles";

	/*
	 * Voting
	 */
	public static final String VOTE_RESULT = "result";

}
