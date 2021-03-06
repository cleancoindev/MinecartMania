package com.afforess.minecartmania.entity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.minecraft.server.v1_4_R1.EntityMinecart;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Furnace;
import org.bukkit.craftbukkit.v1_4_R1.CraftServer;
import org.bukkit.craftbukkit.v1_4_R1.entity.CraftMinecart;
import org.bukkit.craftbukkit.v1_4_R1.entity.CraftPoweredMinecart;
import org.bukkit.craftbukkit.v1_4_R1.entity.CraftStorageMinecart;
import org.bukkit.craftbukkit.v1_4_R1.inventory.CraftItemStack;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.PoweredMinecart;
import org.bukkit.entity.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.Button;
import org.bukkit.material.Lever;
import org.bukkit.material.MaterialData;

import com.afforess.minecartmania.MMMinecart;
import com.afforess.minecartmania.debug.DebugTimer;
import com.afforess.minecartmania.debug.Logger;
import com.afforess.minecartmania.utils.ThreadSafe;

//TODO: get rid of this whole class.

public class MinecartManiaWorld {
	private static ConcurrentHashMap<Integer,MMMinecart> minecarts = new ConcurrentHashMap<Integer,MMMinecart>();
	private static ConcurrentHashMap<Location,MinecartManiaChest> chests = new ConcurrentHashMap<Location,MinecartManiaChest>();
	private static ConcurrentHashMap<Location,MinecartManiaDispenser> dispensers = new ConcurrentHashMap<Location,MinecartManiaDispenser>();
	private static ConcurrentHashMap<Location,MinecartManiaFurnace> furnaces = new ConcurrentHashMap<Location,MinecartManiaFurnace>();
	private static ConcurrentHashMap<String,MinecartManiaPlayer> players = new ConcurrentHashMap<String,MinecartManiaPlayer>();

	private static int counter = 0;
	private static Lock pruneLock = new ReentrantLock();


	public static Set<Integer> replacedIDs = new HashSet<Integer>();



	/**
	 * Returns a new MinecartManiaMinecart from storage if it already exists, or creates and stores a new MinecartManiaMinecart object, and returns it
	 * @param the minecart to wrap
	 */
	@ThreadSafe
	public static MMMinecart getOrCreateMMMinecart(Minecart minecart) {
		prune();
		final int id = minecart.getEntityId();
		MMMinecart testMinecart = minecarts.get(id);
		if (testMinecart == null) {
			Logger.debug("No MM minecart found for id " + id);
			synchronized(minecart) {
				//may have been created while waiting for the lock
				if (minecarts.get(id) != null) {
					return minecarts.get(id);
				}
				//Special handling because bukkit fails at creating the right type of minecart entity

				if(replacedIDs.contains(id)){
					//special case got call for minecart we already replaced, happens when multiple events are queued for the vanilla cart.
					for (Entry<Integer, MMMinecart> ent : minecarts.entrySet()) {
						if(ent.getValue().oldID() == id){
							return ent.getValue();
						}
					}
					Logger.debug("Minecart " + id + " listed as replaced but no entity found!!");
					return null;
				}


				CraftMinecart cm = (CraftMinecart)minecart;	
				EntityMinecart em = (EntityMinecart)cm.getHandle();
				CraftServer server = (CraftServer)Bukkit.getServer();

				if (em.type == 1) {
					CraftStorageMinecart csm = new CraftStorageMinecart(server, em); 
					minecart = (Minecart)csm;
				}   
				else if (em.type == 2) {
					CraftPoweredMinecart csm = new CraftPoweredMinecart(server, em); 
					minecart = (Minecart)csm;
				}
				//End workaround
				MMMinecart newCart;
				if (minecart instanceof StorageMinecart) {
					newCart = new MinecartManiaStorageCart(minecart);
				}
				else {
					newCart = new MMMinecart(minecart);
				}
				minecarts.put(newCart.getEntityId(), newCart);
				replacedIDs.add(id);
				return newCart;
			}
		}
		return testMinecart;
	}

	/**
	 * Returns true if the Minecart with the given entityID was deleted, false if not.
	 * @param the id of the minecart to delete
	 */
	@ThreadSafe
	public static void delMinecartManiaMinecart(int entityID) {
		replacedIDs.remove(minecarts.get(entityID).oldID());
		minecarts.remove(new Integer(entityID));
	}

	@ThreadSafe
	public static void prune() {
		if (pruneLock.tryLock()) {
			try {
				counter++;
				if (counter % 100000 == 0) {
					counter = 0;
					DebugTimer time = new DebugTimer("Pruning");
					int minecart = minecarts.size();
					int chest = chests.size();
					int dispenser = dispensers.size();
					int furnace = furnaces.size();
					pruneFurnaces();
					pruneDispensers();
					pruneChests();
					pruneMinecarts();
					minecart -= minecarts.size();
					chest -= chests.size();
					dispenser -= dispensers.size();
					furnace -= furnaces.size();
					Logger.debug(String.format("Finished Pruning. Removed %d minecarts, %d chests, %d dispensers, and %d furnaces from memory", minecart, chest, dispenser, furnace));
					time.logProcessTime();
				}
			}
			finally {
				pruneLock.unlock();
			}
		}
	}

	public static void pruneFurnaces() {
		Iterator<Entry<Location, MinecartManiaFurnace>> i = furnaces.entrySet().iterator();
		while (i.hasNext()) {
			Entry<Location, MinecartManiaFurnace> e = i.next();
			if (e.getKey().getBlock().getTypeId() != Item.FURNACE.getId() &&  e.getKey().getBlock().getTypeId() != Item.BURNING_FURNACE.getId()) {
				i.remove();
			}
		}
	}

	public static void pruneDispensers() {
		Iterator<Entry<Location, MinecartManiaDispenser>> i = dispensers.entrySet().iterator();
		while (i.hasNext()) {
			Entry<Location, MinecartManiaDispenser> e = i.next();
			if (e.getKey().getBlock().getTypeId() != Item.DISPENSER.getId()) {
				i.remove();
			}
		}
	}

	public static void pruneChests() {
		Iterator<Entry<Location, MinecartManiaChest>> i = chests.entrySet().iterator();
		while (i.hasNext()) {
			Entry<Location, MinecartManiaChest> e = i.next();
			if (e.getKey().getBlock().getTypeId() != Item.CHEST.getId()) {
				i.remove();
			}
		}
	}

	public static void pruneMinecarts() {
		Iterator<Entry<Integer, MMMinecart>> i = minecarts.entrySet().iterator();
		HashSet<Integer> idList = new HashSet<Integer>();
		while (i.hasNext()) {
			Entry<Integer, MMMinecart> e = i.next();
			if (e.getValue().isDead() || e.getValue().isDead()) {
				i.remove();
				replacedIDs.remove(e.getValue().oldID());
			}
			else {
				if (idList.contains(e.getValue().getEntityId())) {
					Logger.severe("Warning! Duplicate minecart's detected! Deleting duplicate. Minecart ID: " + e.getValue().getEntityId());
					i.remove();
					replacedIDs.remove(e.getValue().oldID());
				}
				else {
					idList.add(e.getValue().getEntityId());
				}
			}
		}
	}

	/**
	 ** Returns any minecart at the given location, or null if none is present
	 ** @param the x - coordinate to check
	 ** @param the y - coordinate to check
	 ** @param the z - coordinate to check
	 **/
	@ThreadSafe
	public static MMMinecart getMinecartManiaMinecartAt(int x, int y, int z) {
		Iterator<Entry<Integer, MMMinecart>> i = minecarts.entrySet().iterator();
		while (i.hasNext()) {
			Entry<Integer, MMMinecart> e = i.next();
			if (e.getValue().getLocation().getBlockX() == x) {
				if (e.getValue().getLocation().getBlockY() == y) {
					if (e.getValue().getLocation().getBlockZ() == z) {
						return e.getValue();
					}
				}
			}
		}

		return null;
	}

	/**
	 * Returns an arraylist of all the MinecartManiaMinecarts stored by this class
	 * @return arraylist of all MinecartManiaMinecarts
	 */
	@ThreadSafe
	public static ArrayList<MMMinecart> getMinecartManiaMinecartList() {
		Iterator<Entry<Integer, MMMinecart>> i = minecarts.entrySet().iterator();
		ArrayList<MMMinecart> minecartList = new ArrayList<MMMinecart>(minecarts.size());
		while (i.hasNext()) {
			minecartList.add(i.next().getValue());
		}
		return minecartList;
	}

	/**
	 * Returns a new MinecartManiaChest from storage if it already exists, or creates and stores a new MinecartManiaChest object, and returns it
	 * @param the chest to wrap
	 */
	public static MinecartManiaChest getMinecartManiaChest(Chest chest) {
		MinecartManiaChest testChest = chests.get(new Location(chest.getWorld(), chest.getX(), chest.getY(), chest.getZ()));
		if (testChest == null) {
			MinecartManiaChest newChest = new MinecartManiaChest(chest);
			chests.put(new Location(chest.getWorld(), chest.getX(), chest.getY(), chest.getZ()), newChest);
			return newChest;
		} 
		else {
			//Verify that this block is still a chest (could have been changed)
			if (MinecartManiaWorld.getBlockIdAt(testChest.getWorld(), testChest.getX(), testChest.getY(), testChest.getZ()) == Item.CHEST.getId()) {
				testChest.updateInventory(testChest.getInventory());
				return testChest;
			}
			else {
				chests.remove(new Location(chest.getWorld(), chest.getX(), chest.getY(), chest.getZ()));
				return null;
			}
		}
	}

	/**
	 * Returns true if the chest with the given location was deleted, false if not.
	 * @param the  location of the chest to delete
	 */
	public static boolean delMinecartManiaChest(Location v) {
		if (chests.containsKey(v)) {
			chests.remove(v);
			return true;
		}
		return false;
	}

	/**
	 * Returns an arraylist of all the MinecartManiaChests stored by this class
	 * @return arraylist of all MinecartManiaChest
	 */
	public static ArrayList<MinecartManiaChest> getMinecartManiaChestList() {
		Iterator<Entry<Location, MinecartManiaChest>> i = chests.entrySet().iterator();
		ArrayList<MinecartManiaChest> chestList = new ArrayList<MinecartManiaChest>(chests.size());
		while (i.hasNext()) {
			chestList.add(i.next().getValue());
		}
		return chestList;
	}

	/**
	 ** Returns a new MinecartManiaDispenser from storage if it already exists, or creates and stores a new MinecartManiaDispenser object, and returns it
	 ** @param the dispenser to wrap
	 **/
	public static MinecartManiaDispenser getMinecartManiaDispenser(Dispenser dispenser) {
		MinecartManiaDispenser testDispenser = dispensers.get(new Location(dispenser.getWorld(), dispenser.getX(), dispenser.getY(), dispenser.getZ()));
		if (testDispenser == null) {
			MinecartManiaDispenser newDispenser = new MinecartManiaDispenser(dispenser);
			dispensers.put(new Location(dispenser.getWorld(), dispenser.getX(), dispenser.getY(), dispenser.getZ()), newDispenser);
			return newDispenser;
		} 
		else {
			//Verify that this block is still a dispenser (could have been changed)
			if (MinecartManiaWorld.getBlockIdAt(testDispenser.getWorld(), testDispenser.getX(), testDispenser.getY(), testDispenser.getZ()) == Item.DISPENSER.getId()) {
				testDispenser.updateInventory(testDispenser.getInventory());
				return testDispenser;
			}
			else {
				dispensers.remove(new Location(dispenser.getWorld(), dispenser.getX(), dispenser.getY(), dispenser.getZ()));
				return null;
			}
		}
	}

	/**
	 ** Returns true if the dispenser with the given location was deleted, false if not.
	 ** @param the location of the dispenser to delete
	 **/
	public static boolean delMinecartManiaDispenser(Location v) {
		if (dispensers.containsKey(v)) {
			dispensers.remove(v);
			return true;
		}
		return false;
	}

	/**
	 * Returns an arraylist of all the MinecartManiaDispensers stored by this class
	 * @return arraylist of all MinecartManiaDispensers
	 */
	public static ArrayList<MinecartManiaDispenser> getMinecartManiaDispenserList() {
		Iterator<Entry<Location, MinecartManiaDispenser>> i = dispensers.entrySet().iterator();
		ArrayList<MinecartManiaDispenser> dispenserList = new ArrayList<MinecartManiaDispenser>(dispensers.size());
		while (i.hasNext()) {
			dispenserList.add(i.next().getValue());
		}
		return dispenserList;
	}

	/**
	 ** Returns a new MinecartManiaFurnace from storage if it already exists, or creates and stores a new MinecartManiaFurnace object, and returns it
	 ** @param the furnace to wrap
	 **/
	public static MinecartManiaFurnace getMinecartManiaFurnace(Furnace furnace) {
		MinecartManiaFurnace testFurnace = furnaces.get(new Location(furnace.getWorld(), furnace.getX(), furnace.getY(), furnace.getZ()));
		if (testFurnace == null) {
			MinecartManiaFurnace newFurnace = new MinecartManiaFurnace(furnace);
			furnaces.put(new Location(furnace.getWorld(), furnace.getX(), furnace.getY(), furnace.getZ()), newFurnace);
			return newFurnace;
		} 
		else {
			//Verify that this block is still a furnace (could have been changed)
			if (MinecartManiaWorld.getBlockIdAt(testFurnace.getWorld(), testFurnace.getX(), testFurnace.getY(), testFurnace.getZ()) == Item.FURNACE.getId()
					|| MinecartManiaWorld.getBlockIdAt(testFurnace.getWorld(), testFurnace.getX(), testFurnace.getY(), testFurnace.getZ()) == Item.BURNING_FURNACE.getId()) {
				testFurnace.updateInventory(testFurnace.getInventory());
				return testFurnace;
			}
			else {
				furnaces.remove(new Location(furnace.getWorld(), furnace.getX(), furnace.getY(), furnace.getZ()));
				return null;
			}
		}
	}

	/**
	 ** Returns true if the furnaces with the given location was deleted, false if not.
	 ** @param the location of the furnaces to delete
	 **/
	public static boolean delMinecartManiaFurnace(Location v) {
		if (furnaces.containsKey(v)) {
			furnaces.remove(v);
			return true;
		}
		return false;
	}

	/**
	 * Returns an arraylist of all the MinecartManiaFurnaces stored by this class
	 * @return arraylist of all MinecartManiaFurnaces
	 */
	public static ArrayList<MinecartManiaFurnace> getMinecartManiaFurnaceList() {
		Iterator<Entry<Location, MinecartManiaFurnace>> i = furnaces.entrySet().iterator();
		ArrayList<MinecartManiaFurnace> furnaceList = new ArrayList<MinecartManiaFurnace>(furnaces.size());
		while (i.hasNext()) {
			furnaceList.add(i.next().getValue());
		}
		return furnaceList;
	}

	/**
	 ** Returns a new MinecartManiaPlayer from storage if it already exists, or creates and stores a new MinecartManiaPlayer object, and returns it
	 ** @param the player to wrap
	 **/
	public static MinecartManiaPlayer getMinecartManiaPlayer(Player player) {
		return getMinecartManiaPlayer(player.getName());
	}

	/**
	 ** Returns a new MinecartManiaPlayer from storage if it already exists, or creates and stores a new MinecartManiaPlayer object, and returns it
	 ** @param the name of the player to wrap
	 **/	 
	public static MinecartManiaPlayer getMinecartManiaPlayer(String player) {
		MinecartManiaPlayer testPlayer = players.get(player);
		if (testPlayer == null) {
			testPlayer = new MinecartManiaPlayer(player);
			players.put(player, testPlayer);
		}
		if (testPlayer.isOnline()) {
			testPlayer.updateInventory(testPlayer.getPlayer().getInventory());
		}
		return testPlayer;
	}

	public static void setMinecartManiaPlayer(MinecartManiaPlayer player, String name) {
		players.put(name, player);
	}

	/**
	 * Returns an arraylist of all the MinecartManiaPlayers stored by this class. These players may not be online.
	 * @return arraylist of all MinecartManiaPlayers
	 */
	public static ArrayList<MinecartManiaPlayer> getMinecartManiaPlayerList() {
		Iterator<Entry<String, MinecartManiaPlayer>> i = players.entrySet().iterator();
		ArrayList<MinecartManiaPlayer> playerList = new ArrayList<MinecartManiaPlayer>(players.size());
		while (i.hasNext()) {
			playerList.add(i.next().getValue());
		}
		return playerList;
	}



	/**
	 ** Returns the block at the given x, y, z coordinates
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 **/	
	public static Block getBlockAt(World w, int x, int y, int z) {
		return w.getBlockAt(x, y, z);
	}

	/**
	 ** Returns the block type id at the given x, y, z coordinates
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 **/	
	public static int getBlockIdAt(World w, int x, int y, int z) {
		return w.getBlockTypeIdAt(x, y, z);
	}

	/**
	 ** Returns the block at the given x, y, z coordinates
	 ** @param w World to take effect in
	 ** @param new block type id
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 **/
	public static void setBlockAt(World w, int type, int x, int y, int z) {
		w.getBlockAt(x, y, z).setTypeId(type);
	}

	/**
	 ** Returns the block data at the given x, y, z coordinates
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 **/
	public static byte getBlockData(World w, int x, int y, int z) {
		return w.getBlockAt(x, y, z).getData();
	}

	/**
	 ** sets the block data at the given x, y, z coordinates
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 ** @param new data to set
	 **/
	public static void setBlockData(World w, int x, int y, int z, int data) {
		w.getBlockAt(x, y, z).setData((byte) (data));
	}

	/**
	 ** Returns true if the block at the given x, y, z coordinates is indirectly powered
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 **/
	public static boolean isBlockIndirectlyPowered(World w, int x, int y, int z) {
		return getBlockAt(w, x, y, z).isBlockIndirectlyPowered();
	}

	/**
	 ** Returns true if the block at the given x, y, z coordinates is directly powered
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 **/
	public static boolean isBlockPowered(World w, int x, int y, int z) {
		return getBlockAt(w, x, y, z).isBlockPowered();
	}

	/**
	 ** Sets the block at the given x, y, z coordinates to the given power state, if possible
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 ** @param power state
	 **/
	public static void setBlockPowered(World w, int x, int y, int z, boolean power) {
		MaterialData md = getBlockAt(w, x, y, z).getState().getData();
		int data = getBlockData(w, x, y, z);
		if (getBlockAt(w, x, y, z).getTypeId() == (Item.DIODE_BLOCK_OFF.getId()) && power) {
			setBlockAt(w, Item.DIODE_BLOCK_ON.getId(), x, y, z);
			setBlockData(w, x, y, z, (byte)data);
		}
		else if (getBlockAt(w, x, y, z).getTypeId() == (Item.DIODE_BLOCK_ON.getId()) && !power) {
			setBlockAt(w, Item.DIODE_BLOCK_OFF.getId(), x, y, z);
			setBlockData(w, x, y, z, (byte)data);
		}
		else if (md instanceof Lever || md instanceof Button) {
			setBlockData(w, x, y, z, ((byte)(power ? data | 0x8 : data & 0x7)));
		}
	}

	/**
	 ** Sets the block at the given x, y, z coordinates, as well as any block directly touch the given block to the given power state, if possible
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 ** @param power state
	 **/
	public static void setBlockIndirectlyPowered(World w, int x, int y, int z, boolean power) {
		setBlockPowered(w, x, y, z, power);
		setBlockPowered(w, x-1, y, z, power);
		setBlockPowered(w, x+1, y, z, power);
		setBlockPowered(w, x, y-1, z, power);
		setBlockPowered(w, x, y+1, z, power);
		setBlockPowered(w, x, y, z-1, power);
		setBlockPowered(w, x, y, z+1, power);
	}

	/** Spawns a minecart at the given coordinates. Includes a "fudge factor" to get the minecart to properly line up with minecart tracks.
	 ** @param Location to spawn the minecart at
	 ** @param Material type of minecart to spawn
	 ** @param Owner of this minecart (player or chest). Can be null
	 **/
	public static MMMinecart spawnMinecart(Location l, Item type, Object owner) {
		return spawnMinecart(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), type, owner);
	}

	/** Spawns a minecart at the given coordinates. Includes a "fudge factor" to get the minecart to properly line up with minecart tracks.
	 ** @param w World to take effect in
	 ** @param x coordinate
	 ** @param y coordinate
	 ** @param z coordinate
	 ** @param Material type of minecart to spawn
	 ** @param Owner of this minecart (player or chest). Can be null
	 **/
	private  static MMMinecart spawnMinecart(World w, int x, int y, int z, Item type, Object owner) {
		Location loc = new Location(w, x + 0.5D, y, z + 0.5D);
		Minecart m = null;

		if (type == null || type.getId() == Item.MINECART.getId()) {
			m = (Minecart)w.spawn(loc, Minecart.class);
		}
		else if (type.getId() == Item.POWERED_MINECART.getId()) {
			m = (Minecart)w.spawn(loc, PoweredMinecart.class);
		}
		else if (type.getId() == Item.STORAGE_MINECART.getId()) {
			m = (Minecart)w.spawn(loc, StorageMinecart.class);
		}

		if(m == null || !m.isValid()){
			Logger.debug("Invalid entity spawning minecart at " + loc.toString());
			return null;
		}

		MMMinecart minecart = null;
		String ownerName = "none";
		if (owner != null) {
			if (owner instanceof Player) {
				ownerName = ((Player)owner).getName();
			}
			else if (owner instanceof MinecartManiaPlayer) {
				ownerName = ((MinecartManiaPlayer)owner).getName();
			}
			else if (owner instanceof MinecartManiaChest) {
				ownerName = ((MinecartManiaChest)owner).toString();
			}
		}

		if (m instanceof StorageMinecart) {
			minecart = new MinecartManiaStorageCart(m, ownerName);
		}
		else {
			minecart = new MMMinecart(m, ownerName);
		}

		minecarts.put(minecart.getEntityId(), minecart);

		return minecart;
	}

	public static int getMaxStackSize(ItemStack item) {
		if (item == null) {
			return 64;
		}
		// To Do
		// Find Fix For CraftItemStack
		CraftItemStack stack = CraftItemStack.asCraftCopy(item);
		if (stack.getMaxStackSize() != -1 && !com.afforess.minecartmania.config.Settings.StackAllItems) {
			return stack.getMaxStackSize();
		}
		return 64;
	}


	public static void LoadChunksAround(Location location, int radius) {
		World world = location.getWorld();

		int x = location.getBlock().getChunk().getX();
		int z = location.getBlock().getChunk().getZ();

		Logger.debug("Checking for unloaded chunks around: " + x +"," + z);
		for (int dx = -(radius); dx <= radius; dx++){
			for (int dz = -(radius); dz <= radius; dz++){	
				if (!world.isChunkLoaded(x+dx, z+dz))	{
					Logger.debug("Minecart loading chunk");
					world.loadChunk(x+dx, z+dz);
				}
			}
		}
	}



}