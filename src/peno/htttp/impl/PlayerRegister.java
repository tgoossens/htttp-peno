package peno.htttp.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class PlayerRegister implements Iterable<PlayerInfo> {

	private final Map<String, Map<String, PlayerInfo>> votedPlayers = new HashMap<String, Map<String, PlayerInfo>>();
	private final Set<String> missingPlayers = new HashSet<String>();

	/**
	 * Add a client's player to the registry.
	 * 
	 * <p>
	 * When a player with the same player identifier was missing before, it is
	 * no longer missing.
	 * </p>
	 * 
	 * @param player
	 *            The player.
	 */
	public void addClient(PlayerInfo player) {
		String playerID = player.getPlayerID();
		String clientID = player.getClientID();

		// Get or add client map
		Map<String, PlayerInfo> clients = votedPlayers.get(playerID);
		if (clients == null) {
			clients = new LinkedHashMap<String, PlayerInfo>();
			votedPlayers.put(playerID, clients);
		}

		// Remove missing
		if (missingPlayers.contains(playerID)) {
			missingPlayers.remove(playerID);
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
	 * Check whether the given player is currently missing.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean isMissing(String playerID) {
		return missingPlayers.contains(playerID);
	}

	/**
	 * Check whether there are any players currently missing.
	 */
	public boolean hasMissing() {
		return !missingPlayers.isEmpty();
	}

	/**
	 * Get a set of all currently missing players.
	 */
	public Set<String> getMissing() {
		return Collections.unmodifiableSet(missingPlayers);
	}

	/**
	 * Clear all missing players.
	 */
	public void clearMissing() {
		missingPlayers.clear();
	}

	/**
	 * Mark the given player as missing.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void setMissing(String playerID) {
		votedPlayers.remove(playerID);
		missingPlayers.add(playerID);
	}

	/**
	 * Get the amount of registered players.
	 */
	public int getNbPlayers() {
		return votedPlayers.size();
	}

	/**
	 * Clear all players in the register.
	 */
	public void clear() {
		votedPlayers.clear();
		clearMissing();
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
