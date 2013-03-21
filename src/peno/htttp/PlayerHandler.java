package peno.htttp;

import java.util.List;

/**
 * A handler for player events.
 */
public interface PlayerHandler extends GameHandler {

	/**
	 * Invoked when the player numbers have been rolled.
	 * 
	 * @param playerNumber
	 *            The local player's player number.
	 */
	public void gameRolled(int playerNumber);

	/**
	 * Invoked when the team partner has connected.
	 * 
	 * @param partnerID
	 *            The partner's player identifier.
	 */
	public void teamConnected(String partnerID);

	/**
	 * Invoked when maze tiles have been received from the team partner.
	 * 
	 * @param tiles
	 *            The list of received tiles.
	 */
	public void teamTilesReceived(List<Tile> tiles);

}
