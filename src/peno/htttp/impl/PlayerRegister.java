package peno.htttp.impl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

public class PlayerRegister implements Iterable<PlayerInfo> {

	private final Map<String, Map<String, PlayerInfo>> votedPlayers = new HashMap<String, Map<String, PlayerInfo>>();

	/**
	 * Add a client's player to the registry.
	 * 
	 * @param player
	 *            The player.
	 */
	public void addClient(PlayerInfo player) {
		String clientID = player.getClientID();
		String playerID = player.getPlayerID();

		Map<String, PlayerInfo> clients = votedPlayers.get(playerID);
		if (clients == null) {
			clients = new LinkedHashMap<String, PlayerInfo>();
			votedPlayers.put(playerID, clients);
		}

		// Add as *last* player in the client map
		clients.put(clientID, player);
	}

	/**
	 * Remove a client's player from the registry.
	 * 
	 * @param clientID
	 *            The client identifier.
	 * @param playerID
	 *            The player identifier.
	 */
	public void removeClient(String clientID, String playerID) {
		Map<String, PlayerInfo> clients = votedPlayers.get(playerID);
		if (clients != null) {
			// Remove from client map
			clients.remove(clientID);
			if (clients.isEmpty()) {
				// Clean up empty client map
				votedPlayers.remove(playerID);
			}
		}
	}

	/**
	 * Get the player with the given player identifier.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public PlayerInfo getPlayer(String playerID) {
		return getValidPlayer(votedPlayers.get(playerID));
	}

	/**
	 * Check if a player with the given player identifier is registered.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean hasPlayer(String playerID) {
		return getPlayer(playerID) != null;
	}

	protected PlayerInfo getValidPlayer(Map<String, PlayerInfo> clients) {
		if (clients != null && !clients.isEmpty()) {
			// Get the *first* player info from the client map
			return clients.values().iterator().next();
		} else {
			return null;
		}
	}

	/**
	 * Get the amount of registered players.
	 */
	public int getNbPlayers() {
		return votedPlayers.size();
	}

	public void clear() {
		votedPlayers.clear();
	}

	@Override
	public Iterator<PlayerInfo> iterator() {
		return new Itr();
	}

	private class Itr implements Iterator<PlayerInfo> {

		private Iterator<Map<String, PlayerInfo>> itr;
		private boolean isComputed;
		private PlayerInfo next;

		public Itr() {
			itr = votedPlayers.values().iterator();
		}

		@Override
		public boolean hasNext() {
			if (!isComputed) {
				isComputed = true;
				next = null;
				do {
					if (!itr.hasNext())
						break;
					next = getValidPlayer(itr.next());
				} while (itr.hasNext() && next == null);
			}
			return (next != null);
		}

		@Override
		public PlayerInfo next() {
			if (!hasNext())
				throw new NoSuchElementException();
			isComputed = false;
			return next;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

	}

}
