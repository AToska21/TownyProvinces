package io.github.townyadvanced.townyprovinces.listeners;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.event.PlotChangeTypeEvent;
import com.palmergames.bukkit.towny.event.PlotPreChangeTypeEvent;
import com.palmergames.bukkit.towny.event.PreNewTownEvent;
import com.palmergames.bukkit.towny.event.TownBlockTypeRegisterEvent;
import com.palmergames.bukkit.towny.event.TownPreClaimEvent;
import com.palmergames.bukkit.towny.event.TownUpkeepCalculationEvent;
import com.palmergames.bukkit.towny.event.TownyLoadedDatabaseEvent;
import com.palmergames.bukkit.towny.event.TranslationLoadEvent;
import com.palmergames.bukkit.towny.event.town.TownPreMergeEvent;
import com.palmergames.bukkit.towny.event.town.TownPreSetHomeBlockEvent;
import com.palmergames.bukkit.towny.event.town.TownUnclaimEvent;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.Translatable;
import com.palmergames.bukkit.towny.object.Translation;
import com.palmergames.bukkit.towny.object.TranslationLoader;
import com.palmergames.bukkit.towny.object.WorldCoord;
import io.github.townyadvanced.townyprovinces.TownyProvinces;
import io.github.townyadvanced.townyprovinces.data.TownyProvincesDataHolder;
import io.github.townyadvanced.townyprovinces.messaging.Messaging;
import io.github.townyadvanced.townyprovinces.metadata.TownMetaDataController;
import io.github.townyadvanced.townyprovinces.objects.Province;
import io.github.townyadvanced.townyprovinces.objects.TPCoord;
import io.github.townyadvanced.townyprovinces.objects.TPFinalCoord;
import io.github.townyadvanced.townyprovinces.settings.TownyProvincesSettings;
import io.github.townyadvanced.townyprovinces.util.BiomeUtil;
import io.github.townyadvanced.townyprovinces.util.CustomPlotUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

public class TownyListener implements Listener {

	/* Handle reloading MetaData when Towny's DB is loaded. */
//	@EventHandler
	public void onTownyLoadDB(TownyLoadedDatabaseEvent event) {
	}
	
	/* Handle re-adding the lang string into Towny when Towny reloads the Translation Registry. */
	@EventHandler
	public void onTownyLoadLang(TranslationLoadEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		Plugin plugin = TownyProvinces.getPlugin();
		Path langFolderPath = Paths.get(plugin.getDataFolder().getPath()).resolve("lang");
		TranslationLoader loader = new TranslationLoader(langFolderPath, plugin, TownyProvinces.class);
		loader.load();
		Map<String, Map<String, String>> translations = loader.getTranslations();
		for (String language : translations.keySet())
			for (Map.Entry<String, String> map : translations.get(language).entrySet())
				event.addTranslation(language, map.getKey(), map.getValue());
	}

	@EventHandler(ignoreCancelled = true)
	public void onTownMergeAttempt(TownPreMergeEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		event.setCancelled(true);
		event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + Translatable.of("msg_err_cannot_merge_towns").translate(Locale.ROOT));
	}

	/**
	 * Highest priority as we will assume success and take money here
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onNewTownAttempt(PreNewTownEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		//No restriction if it is not in the TP-enabled world
		if (!event.getTownWorldCoord().getWorldName().equalsIgnoreCase(TownyProvincesSettings.getWorldName())) {
			return;
		}
		/*
		 * Can't place new town outside a province
		 * Note: unless some hacking has occurred,
		 * the only possible place, this can occur,
		 * is on a province border.
		 */
		Coord coord = Coord.parseCoord(event.getTownLocation());
		Province province = TownyProvincesDataHolder.getInstance().getProvinceAtCoord(coord.getX(), coord.getZ());
		if (province == null) {
			event.setCancelled(true);
			event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + Translatable.of("msg_err_cannot_create_town_on_province_border").translate(Locale.ROOT));
			return;
		}
		//Can't place town in Sea province
		if (!province.getType().canNewTownsBeCreated()) {
			String provinceTypeTranslation = Translation.of("word_" + province.getType().name().toLowerCase());
			event.setCancelled(true);
			event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + Translatable.of("msg_err_cannot_create_town_in_province_type", provinceTypeTranslation).translate(Locale.ROOT));
			return;
		}
		//Can't place new town is province-at-location already has one
		if (doesProvinceContainTown(province)) {
			event.setCancelled(true);
			event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + Translatable.of("msg_err_cannot_create_town_in_full_province").translate(Locale.ROOT));
			return;
		}
		/*
		 * Check if the player has enough money
		 * If the player does, pay the region settlement cost
		 */
		if (TownySettings.isUsingEconomy() && province.getNewTownCost() > 0) {
			int regionSettlementCost = (int)(TownyProvincesSettings.isBiomeCostAdjustmentsEnabled() ? province.getBiomeAdjustedNewTownCost() : province.getNewTownCost());
			double totalNewTownCost = TownySettings.getNewTownPrice() + regionSettlementCost;
			Resident resident = TownyAPI.getInstance().getResident(event.getPlayer());
			if (resident != null && resident.getAccountOrNull() != null) {
				if (resident.getAccountOrNull().canPayFromHoldings(totalNewTownCost)) {
					//Pay the region settlement cost
					resident.getAccountOrNull().withdraw(regionSettlementCost, "Region Settlement Cost");
					Messaging.sendMsg(event.getPlayer(), Translatable.of("msg_you_paid_region_settlement_cost", TownyEconomyHandler.getFormattedBalance(regionSettlementCost)));
				} else {
					//Cancel the event
					event.setCancelled(true);
					event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + Translatable.of("msg_err_cannot_afford_new_town", TownyEconomyHandler.getFormattedBalance(totalNewTownCost)).translate(Locale.ROOT));
				}
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void on(TownUpkeepCalculationEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled() || !TownySettings.isUsingEconomy()) {
			return;
		}
		//Add new plot upkeeps
		if(TownyProvincesSettings.isJumpNodesEnabled() && TownMetaDataController.hasJumpNode(event.getTown())) {
			double updatedUpkeepCost = event.getUpkeep() + TownyProvincesSettings.getJumpNodesUpkeepCost();
			event.setUpkeep(updatedUpkeepCost);
		}
		if(TownyProvincesSettings.isPortsEnabled() && TownMetaDataController.hasPort(event.getTown())) {
			double updatedUpkeepCost = event.getUpkeep() + TownyProvincesSettings.getPortsUpkeepCost();
			event.setUpkeep(updatedUpkeepCost);
		}
		//Can't work it if the town has no homeblock
		if (!event.getTown().hasHomeBlock()) {
			return;
		}
		//No extra upkeep if it is not in the TP-enabled world
		if (!event.getTown().hasHomeBlock() && !event.getTown().getWorld().getName().equalsIgnoreCase(TownyProvincesSettings.getWorldName())) {
			return;
		}
		//Add extra upkeep
		Coord coord = event.getTown().getHomeBlockOrNull().getCoord();
		Province province = TownyProvincesDataHolder.getInstance().getProvinceAtCoord(coord.getX(),coord.getZ());
		if(province != null) {
			int regionUpkeepCost = (int)(TownyProvincesSettings.isBiomeCostAdjustmentsEnabled() ? province.getBiomeAdjustedUpkeepTownCost() : province.getUpkeepTownCost());
			if (regionUpkeepCost > 0) {
				double updatedUpkeepCost = event.getUpkeep() + regionUpkeepCost;
				event.setUpkeep(updatedUpkeepCost);
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onTownPreClaimAttempt(TownPreClaimEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		//No restriction if it is not in the TP-enabled world
		World townyProvinceWorld = TownyProvincesSettings.getWorld();
		if (!event.getTownBlock().getWorld().getBukkitWorld().equals(townyProvinceWorld)) {
			return;
		}
		/*
		 * Can't clam outside a province
		 * Note: unless some hacking has occurred,
		 * the only possible place, this can occur,
		 * is on a province border.
		 */
		Province provinceAtClaimLocation = TownyProvincesDataHolder.getInstance().getProvinceAtCoord(event.getTownBlock().getX(), event.getTownBlock().getZ());
		if (provinceAtClaimLocation == null) {
			event.setCancelled(true);
			event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + " " + Translatable.of("msg_err_cannot_claim_land_on_province_border").translate(Locale.ROOT));
			return;
		}
		//Can't claim without homeblock
		if(!event.getTown().hasHomeBlock()) {
			event.setCancelled(true);
			event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + " " + Translatable.of("msg_err_cannot_claim_land_without_homeblock").translate(Locale.ROOT));
			return;
		}
		
		//Apply special rules if claiming outside home province
		Province provinceOfClaimingTown = TownyProvincesDataHolder.getInstance().getProvinceAtCoord(event.getTown().getHomeBlockOrNull().getX(), event.getTown().getHomeBlockOrNull().getZ());
		if(!provinceOfClaimingTown.equals(provinceAtClaimLocation)) {

			//For outposts, cancel if the target province does not allow foreign outposts
			if(event.isOutpost() && !provinceAtClaimLocation.getType().canForeignOutpostsBeCreated()) {
				Translatable errorMessage;
				boolean sea = TownyProvincesSettings.getSeaProvinceOutpostsAllowed();
				boolean wasteland = TownyProvincesSettings.getWastelandProvinceOutpostsAllowed();
				if (sea && wasteland) {
					errorMessage = Translatable.of("msg_err_outpost_claim_rules_home_sea_wasteland");
				} else if (sea) {
					errorMessage = Translatable.of("msg_err_outpost_claim_rules_home_sea");
				} else if (wasteland) {
					errorMessage = Translatable.of("msg_err_outpost_claim_rules_home_wasteland");
				} else {
					errorMessage = Translatable.of("msg_err_outpost_claim_rules_home");
				}
				event.setCancelled(true);
				event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + " " + errorMessage.translate(Locale.ROOT));
				return;
			}
			
			//For any type of claim, cancel if the claiming town already has max-num-townblocks in the province
			int numTownBlocksInProvince = TownyProvincesDataHolder.getInstance().getNumTownBlocksInProvince(event.getTown(), provinceAtClaimLocation);
			if (numTownBlocksInProvince >= TownyProvincesSettings.getMaxNumTownBlocksInEachForeignProvince()) {
				event.setCancelled(true);
				event.setCancelMessage(TownyProvinces.getTranslatedPrefix() + " " + Translatable.of("msg_err_too_many_townblocks_in_province").translate(Locale.ROOT));
				return;
			}
		}
	}
	
	@EventHandler(ignoreCancelled = true)
	private void onPlotPreChangeTypeEvent(PlotPreChangeTypeEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		if (TownyProvincesSettings.isJumpNodesEnabled()) {
			evaluatePrePlotConversionForJumpNodes(event);
		}
		if (TownyProvincesSettings.isPortsEnabled()) {
			evaluatePrePlotConversionForPorts(event);
		}
	}
	
	private void evaluatePrePlotConversionForJumpNodes(PlotPreChangeTypeEvent event) {
		Town town = event.getTownBlock().getTownOrNull();
		if(town == null) {
			return;
		}
		boolean newTypeIsJumpNode = event.getNewType().getName().equalsIgnoreCase("jump-node");
		boolean townHasJumpNode = TownMetaDataController.hasJumpNode(town);

		if(newTypeIsJumpNode) {
			if(townHasJumpNode) {
				//Can't add a second jump node
				event.setCancelled(true);
				event.setCancelMessage(Translatable.of("msg_err_cannot_add_a_second_jump_node").translate(Locale.ROOT));
			} 
		}
	}

	private void evaluatePrePlotConversionForPorts(PlotPreChangeTypeEvent event) {
		Town town = event.getTownBlock().getTownOrNull();
		if(town == null) {
			return;
		}
		boolean newTypeIsPort = event.getNewType().getName().equalsIgnoreCase("port");
		boolean townHasPort = TownMetaDataController.hasPort(town);

		if(newTypeIsPort) {
			if(townHasPort) {
				//Can't add a second port
				event.setCancelled(true);
				event.setCancelMessage(Translatable.of("msg_err_cannot_add_a_second_port").translate(Locale.ROOT));
			} else {
				//Can only create port in water biome
				Coord coord = event.getTownBlock().getCoord();
				TPCoord tpCoord = new TPFinalCoord(coord.getX(), coord.getZ());
				Biome biome = BiomeUtil.lookupBiome(tpCoord, event.getTownBlock().getWorld().getBukkitWorld());
				boolean isWaterBiome = biome.name().toLowerCase().contains("ocean")
						|| biome.name().toLowerCase().contains("beach")
						|| biome.name().toLowerCase().contains("river");
				if(!isWaterBiome) {
					event.setCancelled(true);
					event.setCancelMessage(Translatable.of("msg_err_ports_can_only_be_created_in_ocean_biomes").translate(Locale.ROOT));
				}
			}
		}
	}

	/**
	 * When the travel plot is actually created, register it in metadata
	 * 
	 * @param event the event
	 */
	@EventHandler(ignoreCancelled = true)
	private void onPlotChangeTypeEvent(PlotChangeTypeEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		String newType = event.getNewType().getName();
		Town town = event.getTownBlock().getTownOrNull();
		WorldCoord eventWorldCoord = event.getTownBlock().getWorldCoord();

		if (TownyProvincesSettings.isPortsEnabled()) {
			WorldCoord existingTravelPlotWorldCoord = TownMetaDataController.getPortWorldCoord(town);
			if (existingTravelPlotWorldCoord == null) {
				//If this is an addition, add metadata
				if(newType.equalsIgnoreCase("port")) {
					TownMetaDataController.setPortCoord(town, eventWorldCoord);
					town.save();
					Bukkit.getScheduler().runTask(
						TownyProvinces.getPlugin(),
						() -> TownMetaDataController.addExistingSignsAtPort(town, eventWorldCoord));
				}
			} else {
				//If this is a removal, remove metadata
				if (eventWorldCoord.equals(existingTravelPlotWorldCoord)
						&& !newType.equalsIgnoreCase("port")) {
					TownMetaDataController.removeAllPortMetadata(town);
					town.save();
				}
			}
		}

		if (TownyProvincesSettings.isJumpNodesEnabled()) {
			WorldCoord existingTravelPlotWorldCoord = TownMetaDataController.getJumpNodeWorldCoord(town);
			if (existingTravelPlotWorldCoord == null) {
				//If this is an addition, add metadata
				if (newType.equalsIgnoreCase("jump-node")) {
					TownMetaDataController.setJumpNodeCoord(town, eventWorldCoord);
					town.save();
					Bukkit.getScheduler().runTask(
						TownyProvinces.getPlugin(), 
						() -> TownMetaDataController.addExistingSignsAtJumpNode(town, eventWorldCoord));
				}
			} else {
				//If this is a removal, remove metadata
				if (eventWorldCoord.equals(existingTravelPlotWorldCoord)
					&& !newType.equalsIgnoreCase("jump-node")) {
					TownMetaDataController.removeAllJumpNodeMetadata(town);
					town.save();
				}
			}
		}
	}
	
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	private void onTownUnclaim(TownUnclaimEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		if(event.getTown() == null) {
			return;
		}
		Town town = event.getTown();
		
		if(TownMetaDataController.hasJumpNode(town)) {
			WorldCoord worldCoord = TownMetaDataController.getJumpNodeWorldCoord(town);
			if(worldCoord != null && worldCoord.equals(event.getWorldCoord())) {
				TownMetaDataController.removeAllJumpNodeMetadata(town);
				town.save();
			}
		}

		if(TownMetaDataController.hasPort(town)) {
			WorldCoord worldCoord = TownMetaDataController.getPortWorldCoord(town);
			if(worldCoord != null && worldCoord.equals(event.getWorldCoord())) {
				TownMetaDataController.removeAllPortMetadata(town);
				town.save();
			}
		}
	}
	
	private boolean doesProvinceContainTown(Province province) {
		String worldName = TownyProvincesSettings.getWorldName();
		WorldCoord worldCoord;
		for(TPCoord coord: province.getListOfCoordsInProvince()) {
			worldCoord = new WorldCoord(worldName, coord.getX(), coord.getZ());
			if(!TownyAPI.getInstance().isWilderness(worldCoord)) {
				return true;
			}
		}
		return false;
	}

	// Re-register the TownBlockType when/if Towny reloads itself.
	@EventHandler
	public void onTownyLoadTownBlockTypes(TownBlockTypeRegisterEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		CustomPlotUtil.registerCustomPlots();
	}

	@EventHandler
	public void onPreSetHomeBlockEvent(TownPreSetHomeBlockEvent event) {
		if (!TownyProvincesSettings.isTownyProvincesEnabled()) {
			return;
		}
		if(event.getTown().getHomeBlockOrNull() == null) {
			return;
		}
		//Can't move homeblock outside current province
		Province currentHomeProvince = TownyProvincesDataHolder.getInstance().getProvinceAtWorldCoord(event.getTown().getHomeBlockOrNull().getWorldCoord());
		Province updatedHomeProvince = TownyProvincesDataHolder.getInstance().getProvinceAtWorldCoord(event.getTownBlock().getWorldCoord());
		if(currentHomeProvince != updatedHomeProvince) {
			event.setCancelled(true);
			event.setCancelMessage(Translatable.of("msg_err_cannot_move_homeblock_to_different_province").translate(Locale.ROOT));
		}
	}


}