package net.pavinical.sovereign.economy

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.PlantBlock
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.structure.PoolStructurePiece
import net.minecraft.structure.StructurePlacementData
import net.minecraft.structure.StructureTemplate
import net.minecraft.structure.processor.BlockIgnoreStructureProcessor
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.Heightmap
import net.pavinical.sovereign.Sovereign
import net.pavinical.sovereign.block.ClerkTableBlock
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillagePlotData
import net.pavinical.sovereign.data.VillageTier
import kotlin.math.abs

object VillagePlotService {
    private const val CASTLE_PLOT_SIZE = 25
    private const val MAX_GRADE_DELTA = 3
    private const val ROAD_SEARCH_RADIUS = 72
    private const val PLOT_SPACING_BLOCKS = 8
    private const val FALLBACK_CASTLE_PLATFORM_MARGIN = 3
    private const val TICKS_PER_MINUTE = 20L * 60L
    private val HOUSE_BUILD_COSTS = mapOf(1 to 500, 2 to 750, 3 to 1000)
    private const val CASTLE_BUILD_COST = 5000
    private val HOUSE_BUILD_TIMES = mapOf(
        1 to 2L * TICKS_PER_MINUTE,
        2 to 5L * TICKS_PER_MINUTE,
        3 to 10L * TICKS_PER_MINUTE
    )
    private val HOUSE_RELOCATION_COSTS = mapOf(1 to 500, 2 to 750, 3 to 1000)
    private const val CASTLE_BUILD_TIME = 10L * TICKS_PER_MINUTE
    private const val CASTLE_RELOCATION_COST = 5000
    private val GENERATED_VILLAGE_TEMPLATE_ID = Regex("minecraft:village/[a-z0-9_./-]+")
    private val naturalPlotGround = setOf(
        Blocks.GRASS_BLOCK,
        Blocks.DIRT,
        Blocks.PODZOL,
        Blocks.MYCELIUM,
        Blocks.SAND,
        Blocks.RED_SAND,
        Blocks.GRAVEL,
        Blocks.SNOW_BLOCK
    )

    private data class PlotCandidate(val origin: BlockPos, val facing: Direction)
    private data class PlotBounds(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int)
    private const val STRUCTURE_CLEAR_FLAGS = Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS

    data class PendingPlotPlacement(
        val villageId: String,
        val structureType: String,
        val existingPlotIndex: Int,
        val origin: BlockPos,
        val sizeX: Int,
        val sizeZ: Int,
        val facing: Direction
    ) {
        fun displayName(): String {
            val label = structureType.replaceFirstChar { it.uppercase() }
            val target = if (existingPlotIndex > 0) "plot #$existingPlotIndex" else "new plot"
            return "$label $target, ${sizeX}x$sizeZ facing ${facing.asString()}"
        }
    }

    fun ensurePlots(world: ServerWorld, village: VillageData): Int {
        val centerChanged = VillageCenterService.ensureCenter(world, village)
        val completedBuilds = completeReadyBuilds(world, village)
        val registeredGenerated = registerGeneratedVillageStructures(world, village)
        val target = plotCountFor(village.tier)
        if (target <= 0) {
            if (registeredGenerated > 0 || centerChanged || completedBuilds > 0) {
                world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
            }
            return registeredGenerated
        }

        var created = 0
        val housePlotSize = housePlotSizeFor(village.tier)
        val obsoleteEmptyPlots = village.plots.filterIndexed { index, plot ->
            plot.isEmpty() && !plot.isPending() &&
                !plot.manualPlacement &&
                (plot.allowedType == "house" && plot.sizeX != housePlotSize ||
                    !isValidEmptyPlot(world, plot) ||
                    overlapsProtectedPlot(village.plots, index, plot))
        }
        obsoleteEmptyPlots.forEach { clearPlotOutline(world, it) }
        val removedEmptyPlots = if (obsoleteEmptyPlots.isNotEmpty()) {
            village.plots.removeAll(obsoleteEmptyPlots.toSet())
        } else {
            false
        }
        clearStrayPlotOutlines(world, village)
        village.plots.filter { it.isEmpty() }.forEach { clearPlotOutline(world, it) }
        val occupiedPlots = village.plots.toMutableList()
        var managedPlotCount = village.plots.count { it.allowedType != "generated" }

        for (candidate in roadAdjacentPlotCandidates(world, village.centerPos, housePlotSize, occupiedPlots)) {
            if (managedPlotCount >= target) break
            if (occupiedPlots.any { overlaps(it, candidate.origin, housePlotSize, housePlotSize, candidate.facing) }) continue
            val plot = VillagePlotData(
                origin = candidate.origin,
                sizeX = housePlotSize,
                sizeZ = housePlotSize,
                allowedType = "house",
                facing = candidate.facing
            )
            village.plots += plot
            occupiedPlots += plot
            created++
            managedPlotCount++
        }

        village.plots.filter { it.isEmpty() && !it.isPending() }.forEach { outlinePlot(world, it) }
        if (created > 0 || registeredGenerated > 0 || removedEmptyPlots || centerChanged || completedBuilds > 0) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }
        return created + registeredGenerated
    }

    fun placeManualPlot(world: ServerWorld, village: VillageData, player: PlayerEntity, structureType: String): Text {
        val front = player.blockPos.offset(player.horizontalFacing)
        val placement = previewManualPlot(world, village, player, structureType, 0, front)
            ?: return Text.literal("That site is blocked by roads, buildings, or another plot.").formatted(Formatting.RED)
        return commitManualPlot(world, village, placement)
    }

    fun previewManualPlot(
        world: ServerWorld,
        village: VillageData,
        player: PlayerEntity,
        structureType: String,
        existingOneBasedPlotIndex: Int,
        frontPos: BlockPos
    ): PendingPlotPlacement? {
        val normalizedType = structureType.lowercase()
        val existingPlot = if (existingOneBasedPlotIndex > 0) {
            village.plots.getOrNull(existingOneBasedPlotIndex - 1) ?: return null
        } else {
            null
        }
        if (existingPlot != null && existingPlot.isPending()) return null

        val size = when (normalizedType) {
            "house" -> {
                if (maxHouseLevel(village.tier) < 1) {
                    return null
                }
                existingPlot?.sizeX ?: housePlotSizeFor(village.tier)
            }
            "castle" -> {
                if (village.tier.ordinal < VillageTier.TOWN.ordinal) {
                    return null
                }
                existingPlot?.sizeX ?: CASTLE_PLOT_SIZE
            }
            "generated" -> {
                existingPlot?.sizeX ?: return null
            }
            else -> return null
        }

        val facing = player.horizontalFacing.opposite
        val roughOrigin = BlockPos(frontPos.x, frontPos.y, frontPos.z)
        val yTop = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, roughOrigin.x, roughOrigin.z) - 1
        val origin = BlockPos(roughOrigin.x, yTop + 1, roughOrigin.z)
        val center = plotCenter(origin, size, size, facing)

        if (!isWithinVillageRadius(village.centerPos, center, ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS)) {
            return null
        }
        if (village.plots.withIndex().any { (index, plot) ->
                index != existingOneBasedPlotIndex - 1 && overlaps(plot, origin, size, size, facing)
            }
        ) {
            return null
        }
        if (!arePlotChunksLoaded(world, origin, size, size, facing, FALLBACK_CASTLE_PLATFORM_MARGIN)) {
            return null
        }
        if (!canPrepareManualPlot(world, origin, size, size, facing)) {
            return null
        }

        return PendingPlotPlacement(
            villageId = village.id.toString(),
            structureType = normalizedType,
            existingPlotIndex = existingOneBasedPlotIndex,
            origin = origin,
            sizeX = size,
            sizeZ = size,
            facing = facing
        )
    }

    fun commitManualPlot(world: ServerWorld, village: VillageData, placement: PendingPlotPlacement): Text {
        if (placement.villageId != village.id.toString()) {
            return Text.literal("That marker belongs to another village.").formatted(Formatting.RED)
        }
        if (placement.existingPlotIndex > 0) {
            val existing = village.plots.getOrNull(placement.existingPlotIndex - 1)
                ?: return Text.literal("That plot is no longer available.").formatted(Formatting.RED)
            if (existing.isPending()) {
                return Text.literal("That plot can no longer be moved.").formatted(Formatting.RED)
            }

            val moved = existing.copy(
                origin = placement.origin,
                sizeX = placement.sizeX,
                sizeZ = placement.sizeZ,
                allowedType = placement.structureType,
                facing = placement.facing,
                manualPlacement = true
            )

            if (existing.builtType.isNotBlank()) {
                if (!placeCompletedStructure(world, village, moved)) {
                    return Text.literal("The builders could not raise that structure there.").formatted(Formatting.RED)
                }
                clearBuiltPlotSite(world, existing)
            } else {
                clearPlotOutline(world, existing)
                prepareManualPlotPlatform(world, moved.origin, moved.sizeX, moved.sizeZ, moved.facing)
                outlinePlot(world, moved)
            }

            village.plots[placement.existingPlotIndex - 1] = moved
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
            val label = if (moved.builtType.isNotBlank()) moved.builtType else "plot"
            return Text.literal("Moved $label #${placement.existingPlotIndex} in ${village.name}.").formatted(Formatting.GREEN)
        }

        if (village.plots.any { overlaps(it, placement.origin, placement.sizeX, placement.sizeZ, placement.facing) }) {
            return Text.literal("That site crowds another plot.").formatted(Formatting.RED)
        }
        prepareManualPlotPlatform(world, placement.origin, placement.sizeX, placement.sizeZ, placement.facing)
        val plot = VillagePlotData(
            origin = placement.origin,
            sizeX = placement.sizeX,
            sizeZ = placement.sizeZ,
            allowedType = placement.structureType,
            facing = placement.facing,
            manualPlacement = true
        )
        village.plots += plot
        outlinePlot(world, plot)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        val label = placement.structureType.replaceFirstChar { it.uppercase() }
        return Text.literal("Marked ${placement.sizeX}x${placement.sizeZ} $label plot #${village.plots.size} in ${village.name}.").formatted(Formatting.GREEN)
    }

    fun relocationCost(plot: VillagePlotData): Int {
        return when {
            plot.isPending() || plot.builtType.isBlank() -> 0
            plot.builtType == "castle" || plot.allowedType == "castle" || plot.sizeX >= CASTLE_PLOT_SIZE -> CASTLE_RELOCATION_COST
            plot.builtType == "house" -> HOUSE_RELOCATION_COSTS[plot.buildingLevel.coerceIn(1, 3)] ?: HOUSE_RELOCATION_COSTS.getValue(1)
            else -> HOUSE_RELOCATION_COSTS.getValue(1)
        }
    }

    fun build(world: ServerWorld, village: VillageData, oneBasedPlotIndex: Int, structureType: String): Text {
        ensurePlots(world, village)
        val plot = village.plots.getOrNull(oneBasedPlotIndex - 1)
            ?: return Text.literal("No plot #$oneBasedPlotIndex is available in ${village.name}.").formatted(Formatting.RED)

        if (!plot.isEmpty()) {
            return Text.literal("Plot #$oneBasedPlotIndex already holds ${plot.builtType}.").formatted(Formatting.RED)
        }
        if (plot.isPending()) {
            return Text.literal("Plot #$oneBasedPlotIndex is already under construction.").formatted(Formatting.RED)
        }

        val normalizedType = structureType.lowercase()
        val built = when (normalizedType) {
            "house" -> {
                if (maxHouseLevel(village.tier) < 1) {
                    return Text.literal("Houses unlock at Village level.").formatted(Formatting.RED)
                }
                resizePlot(world, village, plot, 1) ?: return Text.literal("That house plot cannot expand safely.").formatted(Formatting.RED)
                scheduleBuild(world, village, plot, "house", 1, HOUSE_BUILD_TIMES.getValue(1))
                "house"
            }
            "castle" -> {
                if (village.tier.ordinal < VillageTier.TOWN.ordinal) {
                    return Text.literal("Castles unlock at Town level.").formatted(Formatting.RED)
                }
                if (plot.sizeX < CASTLE_PLOT_SIZE || plot.sizeZ < CASTLE_PLOT_SIZE) {
                    return Text.literal("That plot is too small for a castle.").formatted(Formatting.RED)
                }
                scheduleBuild(world, village, plot, "castle", 1, CASTLE_BUILD_TIME)
                "castle"
            }
            else -> return Text.literal("Unknown structure '$structureType'. Use house or castle.").formatted(Formatting.RED)
        }

        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return Text.literal("${built.replaceFirstChar { it.uppercase() }} started on plot #$oneBasedPlotIndex in ${village.name}.").formatted(Formatting.GREEN)
    }

    fun buyBuild(world: ServerWorld, village: VillageData, player: PlayerEntity, oneBasedPlotIndex: Int, structureType: String): Text {
        ensurePlots(world, village)
        val plot = village.plots.getOrNull(oneBasedPlotIndex - 1)
            ?: return Text.literal("No plot #$oneBasedPlotIndex is available in ${village.name}.").formatted(Formatting.RED)
        if (!plot.isEmpty() || plot.isPending()) {
            return Text.literal("Plot #$oneBasedPlotIndex already holds ${plot.builtType}.").formatted(Formatting.RED)
        }

        val normalizedType = structureType.lowercase()
        val cost = when (normalizedType) {
            "house" -> HOUSE_BUILD_COSTS.getValue(1)
            "castle" -> CASTLE_BUILD_COST
            else -> return Text.literal("Unknown structure '$structureType'. Use house or castle.").formatted(Formatting.RED)
        }
        val ledger = PlayerEmeraldLedger.get(world)
        if (!ledger.withdraw(player.uuid, cost)) {
            return Text.literal("You need $cost emeralds for that build.").formatted(Formatting.RED)
        }
        val result = build(world, village, oneBasedPlotIndex, normalizedType)
        val builtPlot = village.plots.getOrNull(oneBasedPlotIndex - 1)
        if (builtPlot == null || !builtPlot.isPending()) {
            ledger.deposit(player.uuid, cost)
            return result
        }
        return Text.literal("${result.string} Paid $cost emeralds.").formatted(Formatting.GREEN)
    }

    fun upgradeHouse(world: ServerWorld, village: VillageData, player: PlayerEntity, oneBasedPlotIndex: Int): Text {
        ensurePlots(world, village)
        val plot = village.plots.getOrNull(oneBasedPlotIndex - 1)
            ?: return Text.literal("No plot #$oneBasedPlotIndex is available in ${village.name}.").formatted(Formatting.RED)
        if (plot.builtType != "house") {
            return Text.literal("Plot #$oneBasedPlotIndex does not hold a house.").formatted(Formatting.RED)
        }
        if (plot.isPending()) {
            return Text.literal("Plot #$oneBasedPlotIndex is already under construction.").formatted(Formatting.RED)
        }
        val currentLevel = plot.buildingLevel.coerceAtLeast(1)
        val nextLevel = currentLevel + 1
        val availableLevel = maxHouseLevel(village.tier)
        if (nextLevel > 3) {
            return Text.literal("That house is already city-grade.").formatted(Formatting.YELLOW)
        }
        if (nextLevel > availableLevel) {
            return Text.literal("House level $nextLevel unlocks at ${tierForHouseLevel(nextLevel).displayName()} level.").formatted(Formatting.RED)
        }
        val cost = HOUSE_BUILD_COSTS.getValue(nextLevel)
        val ledger = PlayerEmeraldLedger.get(world)
        if (!ledger.withdraw(player.uuid, cost)) {
            return Text.literal("You need $cost emeralds for that upgrade.").formatted(Formatting.RED)
        }
        if (resizePlot(world, village, plot, nextLevel) == null) {
            ledger.deposit(player.uuid, cost)
            return Text.literal("That house plot cannot expand safely.").formatted(Formatting.RED)
        }
        scheduleBuild(world, village, plot, "house", nextLevel, HOUSE_BUILD_TIMES.getValue(nextLevel))
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return Text.literal("Started house level $nextLevel on plot #$oneBasedPlotIndex for $cost emeralds.").formatted(Formatting.GREEN)
    }

    fun speedUpBuilds(world: ServerWorld, village: VillageData, ticks: Long): Int {
        var changed = 0
        village.plots.filter { it.isPending() }.forEach { plot ->
            plot.buildReadyTick = (plot.buildReadyTick - ticks).coerceAtLeast(world.time)
            changed++
        }
        changed += completeReadyBuilds(world, village)
        if (changed > 0) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }
        return changed
    }

    fun buildingPlotsFor(world: ServerWorld, village: VillageData): List<BuildingPlot> {
        ensurePlots(world, village)
        val plots = village.plots.mapIndexed { index, plot ->
            val isCastle = plot.allowedType == "castle" || plot.sizeX >= CASTLE_PLOT_SIZE
            val canBuild = plot.isEmpty() && !plot.isPending() && (
                (!isCastle && maxHouseLevel(village.tier) >= 1) ||
                    (isCastle && village.tier.ordinal >= VillageTier.TOWN.ordinal)
                )
            val currentLevel = plot.buildingLevel.coerceAtLeast(0)
            val nextLevel = currentLevel + 1
            val canUpgrade = plot.builtType == "house" &&
                !plot.isPending() &&
                nextLevel <= 3 &&
                nextLevel <= maxHouseLevel(village.tier)
            val pendingLabel = if (plot.isPending()) {
                val type = plot.pendingBuiltType.replaceFirstChar { it.uppercase() }
                if (plot.pendingBuiltType == "house") "$type ${plot.pendingLevel}" else type
            } else {
                ""
            }
            val builtLabel = if (plot.builtType == "house") {
                "House ${plot.buildingLevel.coerceAtLeast(1)}"
            } else if (plot.builtType == "generated") {
                generatedStructureLabel(plot.structureId)
            } else {
                plot.builtType.replaceFirstChar { it.uppercase() }
            }
            val displayName = when {
                plot.isPending() -> "Plot #${index + 1}: $pendingLabel"
                plot.builtType.isNotBlank() -> "Plot #${index + 1}: $builtLabel"
                isCastle -> "Plot #${index + 1}: Castle Site"
                else -> "Plot #${index + 1}: House Site"
            }
            BuildingPlot(
                plotIndex = index + 1,
                item = plotIcon(plot),
                displayName = displayName,
                allowedType = plot.allowedType,
                builtType = plot.builtType,
                pendingType = plot.pendingBuiltType,
                buildingLevel = plot.buildingLevel,
                pendingLevel = plot.pendingLevel,
                sizeX = plot.sizeX,
                sizeZ = plot.sizeZ,
                facing = plot.facing.asString(),
                remainingTicks = if (plot.isPending()) (plot.buildReadyTick - world.time).coerceAtLeast(0L) else 0L,
                buildCost = if (isCastle) CASTLE_BUILD_COST else HOUSE_BUILD_COSTS.getValue(1),
                upgradeCost = if (canUpgrade) HOUSE_BUILD_COSTS.getValue(nextLevel) else 0,
                canBuild = canBuild,
                canUpgrade = canUpgrade
            )
        }.toMutableList()

        if (maxHouseLevel(village.tier) >= 1) {
            val size = housePlotSizeFor(village.tier)
            plots += BuildingPlot(
                plotIndex = -1,
                item = ItemStack(Items.OAK_FENCE),
                displayName = "New House Site",
                allowedType = "house",
                builtType = "",
                pendingType = "",
                buildingLevel = 0,
                pendingLevel = 0,
                sizeX = size,
                sizeZ = size,
                facing = "manual",
                remainingTicks = 0L,
                buildCost = HOUSE_BUILD_COSTS.getValue(1),
                upgradeCost = 0,
                canBuild = true,
                canUpgrade = false
            )
        }
        if (village.tier.ordinal >= VillageTier.TOWN.ordinal) {
            plots += BuildingPlot(
                plotIndex = -2,
                item = ItemStack(Items.STONE_BRICKS),
                displayName = "New Castle Site",
                allowedType = "castle",
                builtType = "",
                pendingType = "",
                buildingLevel = 0,
                pendingLevel = 0,
                sizeX = CASTLE_PLOT_SIZE,
                sizeZ = CASTLE_PLOT_SIZE,
                facing = "manual",
                remainingTicks = 0L,
                buildCost = CASTLE_BUILD_COST,
                upgradeCost = 0,
                canBuild = true,
                canUpgrade = false
            )
        }
        return plots
    }

    fun describe(village: VillageData): List<String> {
        if (village.tier.ordinal < VillageTier.VILLAGE.ordinal) {
            return listOf("Plots unlock at Village level.")
        }
        if (village.plots.isEmpty()) return listOf("No plots generated yet.")
        return village.plots.mapIndexed { index, plot ->
            val level = if (plot.buildingLevel > 0) " level ${plot.buildingLevel}" else ""
            val status = if (plot.isPending()) {
                "building ${plot.pendingBuiltType} level ${plot.pendingLevel} until tick ${plot.buildReadyTick}"
            } else {
                plot.builtType.ifBlank { "empty" } + level
            }
            "#${index + 1} ${plot.sizeX}x${plot.sizeZ} ${plot.allowedType} facing ${plot.facing.asString()} at ${plot.origin.x}, ${plot.origin.y}, ${plot.origin.z}: $status"
        }
    }

    private fun plotIcon(plot: VillagePlotData): ItemStack {
        if (plot.isPending()) return ItemStack(Items.CLOCK)
        return when {
            plot.builtType == "generated" -> generatedStructureIcon(plot.structureId)
            plot.allowedType == "castle" || plot.builtType == "castle" || plot.sizeX >= CASTLE_PLOT_SIZE -> ItemStack(Items.STONE_BRICKS)
            plot.builtType == "house" && plot.buildingLevel >= 3 -> ItemStack(Items.DARK_OAK_DOOR)
            plot.builtType == "house" && plot.buildingLevel >= 2 -> ItemStack(Items.SPRUCE_DOOR)
            plot.builtType == "house" -> ItemStack(Items.OAK_DOOR)
            else -> ItemStack(Items.OAK_FENCE)
        }
    }

    private fun plotCountFor(tier: VillageTier): Int = when {
        tier.ordinal >= VillageTier.CITY.ordinal -> 8
        tier.ordinal >= VillageTier.TOWN.ordinal -> 6
        tier.ordinal >= VillageTier.VILLAGE.ordinal -> 4
        else -> 0
    }

    private fun maxHouseLevel(tier: VillageTier): Int = when {
        tier.ordinal >= VillageTier.CITY.ordinal -> 3
        tier.ordinal >= VillageTier.TOWN.ordinal -> 2
        tier.ordinal >= VillageTier.VILLAGE.ordinal -> 1
        else -> 0
    }

    private fun housePlotSizeFor(tier: VillageTier): Int = when {
        tier.ordinal >= VillageTier.CITY.ordinal -> 13
        tier.ordinal >= VillageTier.TOWN.ordinal -> 11
        tier.ordinal >= VillageTier.VILLAGE.ordinal -> 9
        else -> 0
    }

    private fun housePlotSizeForLevel(level: Int): Int = when (level.coerceIn(1, 3)) {
        1 -> 9
        2 -> 11
        else -> 13
    }

    private fun tierForHouseLevel(level: Int): VillageTier = when (level) {
        1 -> VillageTier.VILLAGE
        2 -> VillageTier.TOWN
        else -> VillageTier.CITY
    }

    private fun VillageTier.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun registerGeneratedVillageStructures(world: ServerWorld, village: VillageData): Int {
        val structureRegistry = world.registryManager.get(RegistryKeys.STRUCTURE)
        val radius = ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS
        val minChunkX = Math.floorDiv(village.centerPos.x - radius, 16)
        val maxChunkX = Math.floorDiv(village.centerPos.x + radius, 16)
        val minChunkZ = Math.floorDiv(village.centerPos.z - radius, 16)
        val maxChunkZ = Math.floorDiv(village.centerPos.z + radius, 16)
        val discovered = mutableListOf<VillagePlotData>()

        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (!world.chunkManager.isChunkLoaded(chunkX, chunkZ)) continue
                val chunk = world.getChunk(chunkX, chunkZ)
                for ((structure, start) in chunk.structureStarts) {
                    val structureId = structureRegistry.getId(structure) ?: continue
                    if (structureId.namespace != "minecraft" || !structureId.path.startsWith("village_")) continue
                    for (piece in start.children) {
                        val poolPiece = piece as? PoolStructurePiece ?: continue
                        val templateId = generatedVillageTemplateId(poolPiece) ?: continue
                        if (!isRelocatableGeneratedVillageTemplate(templateId)) continue
                        val plot = generatedStructurePlot(poolPiece, templateId)
                        if (!isWithinVillageRadius(village.centerPos, plot.center(), radius)) continue
                        if (village.plots.any { isSameGeneratedStructure(it, plot) }) continue
                        if (discovered.any { isSameGeneratedStructure(it, plot) }) continue
                        discovered += plot
                    }
                }
            }
        }

        village.plots += discovered
        return discovered.size
    }

    private fun generatedVillageTemplateId(piece: PoolStructurePiece): String? {
        return GENERATED_VILLAGE_TEMPLATE_ID.find(piece.poolElement.toString())?.value
    }

    private fun isRelocatableGeneratedVillageTemplate(templateId: String): Boolean {
        if ("/zombie/" in templateId) return false
        return "/houses/" in templateId || "/town_centers/" in templateId
    }

    private fun generatedStructurePlot(piece: PoolStructurePiece, templateId: String): VillagePlotData {
        val box = piece.boundingBox
        val sizeX = oddPlotSize(box.maxX - box.minX + 1)
        val sizeZ = oddPlotSize(box.maxZ - box.minZ + 1)
        val origin = BlockPos((box.minX + box.maxX) / 2, box.minY + 1, box.minZ)
        return VillagePlotData(
            origin = origin,
            sizeX = sizeX,
            sizeZ = sizeZ,
            allowedType = "generated",
            facing = Direction.NORTH,
            builtType = "generated",
            structureId = templateId
        )
    }

    private fun oddPlotSize(size: Int): Int {
        val atLeastFive = size.coerceAtLeast(5)
        return if (atLeastFive % 2 == 0) atLeastFive + 1 else atLeastFive
    }

    private fun isSameGeneratedStructure(existing: VillagePlotData, candidate: VillagePlotData): Boolean {
        if (existing.structureId == candidate.structureId && distanceSquared(existing.center(), candidate.center()) <= 4) {
            return true
        }
        return plotsIntersect(existing, candidate)
    }

    private fun plotsIntersect(firstPlot: VillagePlotData, secondPlot: VillagePlotData): Boolean {
        val first = plotBounds(firstPlot)
        val second = plotBounds(secondPlot)
        return first.minX <= second.maxX && first.maxX >= second.minX &&
            first.minZ <= second.maxZ && first.maxZ >= second.minZ
    }

    private fun generatedStructureLabel(structureId: String): String {
        val raw = structureId.substringAfterLast("/")
            .replace(Regex("^[a-z]+_"), "")
            .replace(Regex("_[0-9]+$"), "")
        return raw.split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            .ifBlank { "Village Building" }
    }

    private fun generatedStructureIcon(structureId: String): ItemStack {
        val id = structureId.lowercase()
        val item = when {
            "library" in id -> Items.BOOKSHELF
            "temple" in id -> Items.BREWING_STAND
            "tool_smith" in id -> Items.SMITHING_TABLE
            "weaponsmith" in id || "weapon_smith" in id -> Items.GRINDSTONE
            "armorer" in id || "armor" in id -> Items.BLAST_FURNACE
            "cartographer" in id -> Items.CARTOGRAPHY_TABLE
            "butcher" in id -> Items.SMOKER
            "fisher" in id -> Items.BARREL
            "mason" in id -> Items.STONECUTTER
            "fletcher" in id -> Items.FLETCHING_TABLE
            "shepherd" in id -> Items.LOOM
            "tannery" in id -> Items.CAULDRON
            "meeting" in id || "fountain" in id -> Items.BELL
            "farm" in id -> Items.WHEAT
            else -> Items.OAK_DOOR
        }
        return ItemStack(item)
    }

    private fun roadAdjacentPlotCandidates(
        world: ServerWorld,
        center: BlockPos,
        size: Int,
        occupiedPlots: List<VillagePlotData>
    ): List<PlotCandidate> {
        return roadPositionsNear(world, center, ROAD_SEARCH_RADIUS)
            .flatMap { road ->
                Direction.Type.HORIZONTAL.map { direction ->
                    PlotCandidate(
                        origin = roadAdjacentPlotOrigin(world, road, direction, size),
                        facing = direction.opposite
                    )
                }
            }
            .distinctBy { "${it.origin.x}:${it.origin.z}" }
            .filter { candidate ->
                isSuitablePlot(world, candidate.origin, size, size, facing = candidate.facing) &&
                    roadTouchesPlot(world, candidate.origin, size, size, candidate.facing) &&
                    occupiedPlots.none { overlaps(it, candidate.origin, size, size, candidate.facing) }
            }
            .sortedBy { distanceSquared(center, it.origin) }
    }

    private fun roadAdjacentPlotOrigin(world: ServerWorld, road: BlockPos, direction: Direction, size: Int): BlockPos {
        val rough = when (direction) {
            Direction.NORTH -> BlockPos(road.x, road.y, road.z - 1)
            Direction.SOUTH -> BlockPos(road.x, road.y, road.z + 1)
            Direction.WEST -> BlockPos(road.x - 1, road.y, road.z)
            Direction.EAST -> BlockPos(road.x + 1, road.y, road.z)
            else -> road
        }
        return surfacePlotOrigin(world, rough)
    }

    private fun roadPositionsNear(world: ServerWorld, center: BlockPos, radius: Int): List<BlockPos> {
        val roads = mutableListOf<BlockPos>()
        for (x in center.x - radius..center.x + radius) {
            for (z in center.z - radius..center.z + radius) {
                val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val pos = BlockPos(x, y, z)
                if (isVillageRoadBlock(world, pos)) roads += pos
            }
        }
        return roads
    }

    private fun surfacePlotOrigin(world: ServerWorld, pos: BlockPos): BlockPos {
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.x, pos.z)
        return BlockPos(pos.x, y, pos.z)
    }

    private fun isValidEmptyPlot(world: ServerWorld, plot: VillagePlotData): Boolean {
        return isSuitablePlot(world, plot.origin, plot.sizeX, plot.sizeZ, allowOwnOutline = true, facing = plot.facing)
    }

    private fun overlapsProtectedPlot(plots: List<VillagePlotData>, plotIndex: Int, plot: VillagePlotData): Boolean {
        return plots.withIndex().any { (otherIndex, other) ->
            otherIndex != plotIndex &&
                (!other.isEmpty() || otherIndex < plotIndex) &&
                overlaps(other, plot.origin, plot.sizeX, plot.sizeZ, plot.facing)
        }
    }

    private fun roadTouchesPlot(world: ServerWorld, origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction): Boolean {
        val bounds = plotBounds(origin, sizeX, sizeZ, facing)
        for (x in bounds.minX - 1..bounds.maxX + 1) {
            if (isVillageRoadColumn(world, x, bounds.minZ - 1)) return true
            if (isVillageRoadColumn(world, x, bounds.maxZ + 1)) return true
        }
        for (z in bounds.minZ - 1..bounds.maxZ + 1) {
            if (isVillageRoadColumn(world, bounds.minX - 1, z)) return true
            if (isVillageRoadColumn(world, bounds.maxX + 1, z)) return true
        }
        return false
    }

    private fun isVillageRoadColumn(world: ServerWorld, x: Int, z: Int): Boolean {
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        return isVillageRoadBlock(world, BlockPos(x, y, z))
    }

    private fun isVillageRoadBlock(world: ServerWorld, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return state.isOf(Blocks.DIRT_PATH) ||
            state.isOf(Blocks.SANDSTONE) ||
            state.isOf(Blocks.SMOOTH_SANDSTONE) ||
            state.isOf(Blocks.CUT_SANDSTONE)
    }

    private fun highestSurfaceY(world: ServerWorld, origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction): Int {
        val bounds = plotBounds(origin, sizeX, sizeZ, facing)
        var highest = world.bottomY
        for (x in bounds.minX - FALLBACK_CASTLE_PLATFORM_MARGIN..bounds.maxX + FALLBACK_CASTLE_PLATFORM_MARGIN) {
            for (z in bounds.minZ - FALLBACK_CASTLE_PLATFORM_MARGIN..bounds.maxZ + FALLBACK_CASTLE_PLATFORM_MARGIN) {
                highest = maxOf(highest, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1)
            }
        }
        return highest
    }

    private fun canPrepareManualPlot(world: ServerWorld, origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction): Boolean {
        val platformY = origin.y - 1
        val bounds = plotBounds(origin, sizeX, sizeZ, facing)
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val groundPos = BlockPos(x, groundY, z)
                val ground = world.getBlockState(groundPos)
                if (isVillageRoadBlock(world, groundPos)) return false
                if (!isNaturalPlotGround(ground) && !ground.isOf(Blocks.COBBLESTONE) && !ground.isOf(Blocks.STONE_BRICKS)) return false
                for (y in minOf(groundY, platformY) + 1..platformY + 6) {
                    val state = world.getBlockState(BlockPos(x, y, z))
                    if (state.isAir) continue
                    if (state.block is PlantBlock || state.isOf(Blocks.SNOW) || isNaturalPlotGround(state)) continue
                    return false
                }
            }
        }
        return true
    }

    private fun prepareManualPlotPlatform(world: ServerWorld, origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction) {
        val platformY = origin.y - 1
        val bounds = plotBounds(origin, sizeX, sizeZ, facing)
        val platformBox = BlockBox(
            bounds.minX,
            platformY + 1,
            bounds.minZ,
            bounds.maxX,
            platformY + 1,
            bounds.maxZ
        )
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val fillBaseY = minOf(groundY, platformY - 1)
                for (y in fillBaseY + 1 until platformY) {
                    setBlock(world, x, y, z, Blocks.DIRT)
                }
                setBlock(world, x, platformY, z, Blocks.GRASS_BLOCK)
                for (y in platformY + 1..platformY + 6) {
                    val state = world.getBlockState(BlockPos(x, y, z))
                    if (state.isAir) continue
                    if (state.block is PlantBlock || state.isOf(Blocks.SNOW) || isNaturalPlotGround(state)) {
                        setBlock(world, x, y, z, Blocks.AIR)
                    }
                }
            }
        }
        blendFoundationIntoTerrain(world, platformBox)
    }

    private fun arePlotChunksLoaded(
        world: ServerWorld,
        origin: BlockPos,
        sizeX: Int,
        sizeZ: Int,
        facing: Direction,
        margin: Int = 0
    ): Boolean {
        val bounds = plotBounds(origin, sizeX, sizeZ, facing)
        val box = BlockBox(
            bounds.minX - margin,
            origin.y - 8,
            bounds.minZ - margin,
            bounds.maxX + margin,
            origin.y + 8,
            bounds.maxZ + margin
        )
        return areTemplateChunksLoaded(world, box)
    }

    private fun isWithinVillageRadius(center: BlockPos, pos: BlockPos, radius: Int): Boolean {
        val dx = center.x - pos.x
        val dz = center.z - pos.z
        return dx * dx + dz * dz <= radius * radius
    }

    private fun overlaps(plot: VillagePlotData, origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction): Boolean {
        val padding = PLOT_SPACING_BLOCKS
        val first = plotBounds(plot)
        val second = plotBounds(origin, sizeX, sizeZ, facing)
        val firstMinX = first.minX - padding
        val firstMaxX = first.maxX + padding
        val firstMinZ = first.minZ - padding
        val firstMaxZ = first.maxZ + padding
        val secondMinX = second.minX
        val secondMaxX = second.maxX
        val secondMinZ = second.minZ
        val secondMaxZ = second.maxZ
        return firstMinX <= secondMaxX && firstMaxX >= secondMinX &&
            firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ
    }

    private fun plotBounds(plot: VillagePlotData): PlotBounds = plotBounds(plot.origin, plot.sizeX, plot.sizeZ, plot.facing)

    private fun plotCenter(origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction): BlockPos {
        val halfX = sizeX / 2
        val halfZ = sizeZ / 2
        return when (facing) {
            Direction.NORTH -> origin.add(0, 0, halfZ)
            Direction.SOUTH -> origin.add(0, 0, -halfZ)
            Direction.WEST -> origin.add(halfX, 0, 0)
            Direction.EAST -> origin.add(-halfX, 0, 0)
            else -> origin
        }
    }

    private fun plotBounds(origin: BlockPos, sizeX: Int, sizeZ: Int, facing: Direction): PlotBounds {
        val halfX = sizeX / 2
        val halfZ = sizeZ / 2
        return when (facing) {
            Direction.NORTH -> PlotBounds(origin.x - halfX, origin.x + halfX, origin.z, origin.z + sizeZ - 1)
            Direction.SOUTH -> PlotBounds(origin.x - halfX, origin.x + halfX, origin.z - sizeZ + 1, origin.z)
            Direction.WEST -> PlotBounds(origin.x, origin.x + sizeX - 1, origin.z - halfZ, origin.z + halfZ)
            Direction.EAST -> PlotBounds(origin.x - sizeX + 1, origin.x, origin.z - halfZ, origin.z + halfZ)
            else -> PlotBounds(origin.x - halfX, origin.x + halfX, origin.z, origin.z + sizeZ - 1)
        }
    }

    private fun isEdge(bounds: PlotBounds, x: Int, z: Int): Boolean {
        return x == bounds.minX || x == bounds.maxX || z == bounds.minZ || z == bounds.maxZ
    }

    private fun distanceSquared(first: BlockPos, second: BlockPos): Int {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun isSuitablePlot(
        world: ServerWorld,
        origin: BlockPos,
        sizeX: Int,
        sizeZ: Int,
        allowOwnOutline: Boolean = false,
        facing: Direction = Direction.NORTH,
        maxGradeDelta: Int = MAX_GRADE_DELTA
    ): Boolean {
        val originGroundY = origin.y - 1
        val bounds = plotBounds(origin, sizeX, sizeZ, facing)
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                if (abs(groundY - originGroundY) > maxGradeDelta) return false
                val groundPos = BlockPos(x, groundY, z)
                val ground = world.getBlockState(groundPos)
                val edge = isEdge(bounds, x, z)
                val ownOutline = allowOwnOutline && edge && (
                    ground.isOf(Blocks.COARSE_DIRT) ||
                        world.getBlockState(BlockPos(x, groundY + 1, z)).isOf(Blocks.OAK_FENCE)
                    )
                if (!ownOutline && !isNaturalPlotGround(ground)) return false
                if (isVillageRoadBlock(world, groundPos)) return false
                if (!isClearAbove(world, x, groundY, z, allowOwnOutline && edge)) return false
            }
        }
        return true
    }

    private fun isNaturalPlotGround(state: BlockState): Boolean {
        return naturalPlotGround.any { state.isOf(it) }
    }

    private fun isClearAbove(world: ServerWorld, x: Int, groundY: Int, z: Int, allowOwnOutline: Boolean = false): Boolean {
        for (y in groundY + 1..groundY + 4) {
            val state = world.getBlockState(BlockPos(x, y, z))
            if (allowOwnOutline && state.isOf(Blocks.OAK_FENCE)) continue
            if (!state.isAir && state.block !is PlantBlock && !state.isOf(Blocks.SNOW)) return false
        }
        return true
    }

    private fun outlinePlot(world: ServerWorld, plot: VillagePlotData) {
        val bounds = plotBounds(plot)
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                if (!isEdge(bounds, x, z)) continue
                val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                setBlock(world, x, groundY + 1, z, Blocks.OAK_FENCE)
            }
        }
    }

    private fun clearPlotOutline(world: ServerWorld, plot: VillagePlotData) {
        val baseY = plot.origin.y - 1
        val bounds = plotBounds(plot)
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                if (!isEdge(bounds, x, z)) continue
                for (y in baseY - MAX_GRADE_DELTA..baseY + MAX_GRADE_DELTA) {
                    val pos = BlockPos(x, y, z)
                    if (world.getBlockState(pos).isOf(Blocks.COARSE_DIRT)) {
                        setBlock(world, x, y, z, Blocks.GRASS_BLOCK)
                    }
                    val fencePos = BlockPos(x, y + 1, z)
                    if (world.getBlockState(fencePos).isOf(Blocks.OAK_FENCE)) {
                        setBlock(world, x, y + 1, z, Blocks.AIR)
                    }
                }
            }
        }
    }

    private fun clearStrayPlotOutlines(world: ServerWorld, village: VillageData) {
        val center = village.centerPos
        val keepEdges = village.plots
            .filter { it.isEmpty() && !it.isPending() }
            .flatMap { plot -> plotEdgeColumns(plot) }
            .toHashSet()
        for (x in center.x - ROAD_SEARCH_RADIUS..center.x + ROAD_SEARCH_RADIUS) {
            for (z in center.z - ROAD_SEARCH_RADIUS..center.z + ROAD_SEARCH_RADIUS) {
                if (BlockPos(x, 0, z) in keepEdges) continue
                val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val pos = BlockPos(x, groundY, z)
                if (world.getBlockState(pos).isOf(Blocks.COARSE_DIRT)) {
                    setBlock(world, x, groundY, z, Blocks.GRASS_BLOCK)
                }
            }
        }
    }

    private fun plotEdgeColumns(plot: VillagePlotData): List<BlockPos> {
        val columns = mutableListOf<BlockPos>()
        val bounds = plotBounds(plot)
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                if (isEdge(bounds, x, z)) {
                    columns += BlockPos(x, 0, z)
                }
            }
        }
        return columns
    }

    private fun scheduleBuild(
        world: ServerWorld,
        village: VillageData,
        plot: VillagePlotData,
        builtType: String,
        level: Int,
        durationTicks: Long
    ) {
        clearPlotOutline(world, plot)
        plot.pendingBuiltType = builtType
        plot.pendingLevel = level
        plot.buildReadyTick = world.time + durationTicks
        plot.structureId = structureIdFor(village, builtType, level)
    }

    private fun completeReadyBuilds(world: ServerWorld, village: VillageData): Int {
        var completed = 0
        village.plots.filter { it.isPending() && it.buildReadyTick <= world.time }.forEach { plot ->
            val builtType = plot.pendingBuiltType
            val level = plot.pendingLevel.coerceAtLeast(1)
            val placed = when (builtType) {
                "house" -> placeHouse(world, village, plot, level)
                "castle" -> placeCastle(world, village, plot)
                else -> false
            }
            if (placed) {
                val oldTier = village.tier
                plot.builtType = builtType
                plot.buildingLevel = level
                plot.structureId = structureIdFor(village, builtType, level)
                plot.pendingBuiltType = ""
                plot.pendingLevel = 0
                plot.buildReadyTick = 0L
                VillageProgression.addBuildingExperience(village, builtType, level)
                VillageLevelUpCelebration.play(world, village, oldTier)
                completed++
            }
        }
        return completed
    }

    private fun resizePlot(world: ServerWorld, village: VillageData, plot: VillagePlotData, houseLevel: Int): VillagePlotData? {
        val size = housePlotSizeForLevel(houseLevel)
        if (plot.sizeX == size && plot.sizeZ == size) return plot
        if (village.plots.any { other ->
                other.id != plot.id && overlaps(other, plot.origin, size, size, plot.facing)
            }
        ) {
            return null
        }
        if (plot.isEmpty() && !plot.isPending()) {
            clearPlotOutline(world, plot)
        }
        plot.sizeX = size
        plot.sizeZ = size
        return plot
    }

    private fun placeHouse(world: ServerWorld, village: VillageData, plot: VillagePlotData, level: Int): Boolean {
        val structureName = "minecraft/house${level}_${biomeVariant(village)}"
        return placePlotStructure(world, plot, structureName)
    }

    private fun placeCastle(world: ServerWorld, village: VillageData, plot: VillagePlotData): Boolean {
        val structureName = "minecraft/castle_${biomeVariant(village)}"
        return placePlotStructure(world, plot, structureName)
    }

    private fun placeCompletedStructure(world: ServerWorld, village: VillageData, plot: VillagePlotData): Boolean {
        return when (plot.builtType) {
            "house" -> placeHouse(world, village, plot, plot.buildingLevel.coerceIn(1, 3))
            "castle" -> placeCastle(world, village, plot)
            "generated" -> plot.structureId.takeIf { it.isNotBlank() }?.let { placePlotStructure(world, plot, it) } ?: false
            else -> false
        }
    }

    private fun placePlotStructure(world: ServerWorld, plot: VillagePlotData, structureName: String): Boolean {
        val template = getTemplate(world, structureName) ?: return false
        val rotation = rotationForFacing(plot.facing)
        val placementData = StructurePlacementData()
            .setRotation(rotation)
            .addProcessor(BlockIgnoreStructureProcessor.IGNORE_STRUCTURE_BLOCKS)
        val initialOrigin = BlockPos(plot.origin.x, plot.origin.y + structureYOffset(structureName), plot.origin.z)
        val initialBox = template.calculateBoundingBox(placementData, initialOrigin)
        val targetCenter = plot.center()
        val boxCenterX = (initialBox.minX + initialBox.maxX) / 2
        val boxCenterZ = (initialBox.minZ + initialBox.maxZ) / 2
        val origin = initialOrigin.add(targetCenter.x - boxCenterX, 0, targetCenter.z - boxCenterZ)
        val box = template.calculateBoundingBox(placementData, origin)
        if (!areTemplateChunksLoaded(world, box)) return false

        clearPlotOutline(world, plot)
        buildFoundation(world, box)
        if (structureName == "minecraft/house2_plains") {
            grassUnderFootprint(world, box)
        }
        blendFoundationIntoTerrain(world, box)
        clearPlotBuildVolume(world, plot)
        return template.place(world, origin, origin, placementData, world.random, 2)
    }

    private fun getTemplate(world: ServerWorld, structureName: String): StructureTemplate? {
        val templateManager = world.server.structureTemplateManager
        if (":" in structureName) {
            val namespace = structureName.substringBefore(":")
            val path = structureName.substringAfter(":")
            return templateManager.getTemplate(Identifier.of(namespace, path)).orElse(null)
        }
        return templateManager.getTemplate(Identifier.of(Sovereign.MOD_ID, structureName)).orElse(null)
            ?: templateManager.getTemplate(Identifier.ofVanilla(structureName)).orElse(null)
    }

    private fun structureYOffset(structureName: String): Int {
        if (structureName.startsWith("minecraft:village/")) return 0
        return when (structureName) {
            "minecraft/castle_plains" -> -3
            "minecraft/house2_plains" -> 0
            else -> -1
        }
    }

    private fun areTemplateChunksLoaded(world: ServerWorld, box: BlockBox): Boolean {
        val minChunkX = Math.floorDiv(box.minX, 16)
        val maxChunkX = Math.floorDiv(box.maxX, 16)
        val minChunkZ = Math.floorDiv(box.minZ, 16)
        val maxChunkZ = Math.floorDiv(box.maxZ, 16)
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (!world.chunkManager.isChunkLoaded(chunkX, chunkZ)) return false
            }
        }
        return true
    }

    private fun clearPlotBuildVolume(world: ServerWorld, plot: VillagePlotData) {
        val bounds = plotBounds(plot)
        clearBox(world, bounds.minX + 1, plot.origin.y, bounds.minZ + 1, bounds.maxX - 1, plot.origin.y + 24, bounds.maxZ - 1)
    }

    private fun clearBuiltPlotSite(world: ServerWorld, plot: VillagePlotData) {
        val bounds = plotBounds(plot)
        clearBox(world, bounds.minX, plot.origin.y - 6, bounds.minZ, bounds.maxX, plot.origin.y + 32, bounds.maxZ)
        restoreClearedPlotFootprint(world, plot)
    }

    private fun restoreClearedPlotFootprint(world: ServerWorld, plot: VillagePlotData) {
        val bounds = plotBounds(plot)
        val surfaceY = plot.origin.y - 1
        val box = BlockBox(bounds.minX, surfaceY + 1, bounds.minZ, bounds.maxX, surfaceY + 1, bounds.maxZ)
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                for (y in plot.origin.y - 6 until surfaceY) {
                    setBlock(world, x, y, z, Blocks.DIRT, STRUCTURE_CLEAR_FLAGS)
                }
                setBlock(world, x, surfaceY, z, Blocks.GRASS_BLOCK, STRUCTURE_CLEAR_FLAGS)
            }
        }
        blendFoundationIntoTerrain(world, box)
    }

    private fun buildFoundation(world: ServerWorld, box: BlockBox) {
        val foundationTopY = box.minY - 1
        for (x in box.minX - 1..box.maxX + 1) {
            for (z in box.minZ - 1..box.maxZ + 1) {
                val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                if (groundY >= foundationTopY) continue
                val foundationBlock = foundationBlockFor(world.getBlockState(BlockPos(x, groundY, z)))
                for (y in groundY + 1..foundationTopY) {
                    setBlock(world, x, y, z, foundationBlock)
                }
            }
        }
    }

    private fun grassUnderFootprint(world: ServerWorld, box: BlockBox) {
        val foundationTopY = box.minY - 1
        for (x in box.minX..box.maxX) {
            for (z in box.minZ..box.maxZ) {
                setBlock(world, x, foundationTopY, z, Blocks.GRASS_BLOCK)
            }
        }
    }

    private fun foundationBlockFor(ground: BlockState): Block = when {
        ground.isOf(Blocks.SAND) -> Blocks.SANDSTONE
        ground.isOf(Blocks.RED_SAND) -> Blocks.RED_SANDSTONE
        ground.isOf(Blocks.SNOW_BLOCK) -> Blocks.SNOW_BLOCK
        ground.isOf(Blocks.STONE) ||
            ground.isOf(Blocks.COBBLESTONE) ||
            ground.isOf(Blocks.STONE_BRICKS) -> Blocks.COBBLESTONE
        else -> Blocks.DIRT
    }

    private fun blendFoundationIntoTerrain(world: ServerWorld, box: BlockBox) {
        val foundationTopY = box.minY - 1
        val blendRadius = 4
        for (x in box.minX - blendRadius..box.maxX + blendRadius) {
            for (z in box.minZ - blendRadius..box.maxZ + blendRadius) {
                if (x in box.minX..box.maxX && z in box.minZ..box.maxZ) continue
                val distance = distanceFromBox(x, z, box)
                if (distance !in 1..blendRadius) continue
                val targetY = foundationTopY - distance + 1
                shapeNaturalColumn(world, x, z, targetY)
            }
        }
    }

    private fun distanceFromBox(x: Int, z: Int, box: BlockBox): Int {
        val dx = when {
            x < box.minX -> box.minX - x
            x > box.maxX -> x - box.maxX
            else -> 0
        }
        val dz = when {
            z < box.minZ -> box.minZ - z
            z > box.maxZ -> z - box.maxZ
            else -> 0
        }
        return maxOf(dx, dz)
    }

    private fun shapeNaturalColumn(world: ServerWorld, x: Int, z: Int, targetY: Int) {
        val groundY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        val groundPos = BlockPos(x, groundY, z)
        val ground = world.getBlockState(groundPos)
        if (isVillageRoadBlock(world, groundPos)) return
        if (!isNaturalPlotGround(ground) && !ground.isOf(Blocks.COBBLESTONE) && !ground.isOf(Blocks.STONE_BRICKS)) return

        val above = world.getBlockState(BlockPos(x, groundY + 1, z))
        if (!above.isAir && above.block !is PlantBlock && !above.isOf(Blocks.SNOW)) return

        val fillBlock = foundationBlockFor(ground)
        if (groundY < targetY) {
            for (y in groundY + 1..targetY) {
                setBlock(world, x, y, z, fillBlock)
            }
        }
    }

    private fun rotationForFacing(facing: Direction): BlockRotation = when (facing) {
        Direction.NORTH -> BlockRotation.NONE
        Direction.EAST -> BlockRotation.CLOCKWISE_90
        Direction.SOUTH -> BlockRotation.CLOCKWISE_180
        Direction.WEST -> BlockRotation.COUNTERCLOCKWISE_90
        else -> BlockRotation.NONE
    }

    private fun structureIdFor(village: VillageData, builtType: String, level: Int): String {
        return when (builtType) {
            "house" -> "${Sovereign.MOD_ID}:minecraft/house${level.coerceIn(1, 3)}_${biomeVariant(village)}"
            "castle" -> "${Sovereign.MOD_ID}:minecraft/castle_${biomeVariant(village)}"
            else -> "${Sovereign.MOD_ID}:$builtType"
        }
    }

    private fun biomeVariant(village: VillageData): String {
        val key = village.biome.lowercase()
        return when {
            "desert" in key -> "desert"
            "snow" in key || "ice" in key || "frozen" in key -> "snowy"
            "taiga" in key || "spruce" in key -> "taiga"
            "savanna" in key || "savannah" in key -> "savanna"
            else -> "plains"
        }
    }

    private fun buildHouse(world: ServerWorld, plot: VillagePlotData) {
        val center = plot.center()
        val y = center.y
        val minX = center.x - 4
        val maxX = center.x + 4
        val minZ = center.z - 4
        val maxZ = center.z + 4
        clearBox(world, minX, y, minZ, maxX, y + 6, maxZ)

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                setBlock(world, x, y - 1, z, Blocks.COBBLESTONE)
                setBlock(world, x, y, z, Blocks.OAK_PLANKS)
            }
        }

        for (level in 1..3) {
            for (x in minX..maxX) {
                setBlock(world, x, y + level, minZ, wallBlockFor(x, minX, maxX))
                setBlock(world, x, y + level, maxZ, wallBlockFor(x, minX, maxX))
            }
            for (z in minZ..maxZ) {
                setBlock(world, minX, y + level, z, wallBlockFor(z, minZ, maxZ))
                setBlock(world, maxX, y + level, z, wallBlockFor(z, minZ, maxZ))
            }
        }

        setLocalBlock(world, center, 0, y + 1, -4, plot.facing, Blocks.AIR)
        setLocalBlock(world, center, 0, y + 2, -4, plot.facing, Blocks.AIR)
        setLocalBlock(world, center, 0, y + 1, -5, plot.facing, Blocks.OAK_PRESSURE_PLATE)
        setLocalBlock(world, center, -2, y + 2, -4, plot.facing, Blocks.GLASS_PANE)
        setLocalBlock(world, center, 2, y + 2, -4, plot.facing, Blocks.GLASS_PANE)
        setLocalBlock(world, center, -2, y + 2, 4, plot.facing, Blocks.GLASS_PANE)
        setLocalBlock(world, center, 2, y + 2, 4, plot.facing, Blocks.GLASS_PANE)

        for (x in minX - 1..maxX + 1) {
            for (z in minZ - 1..maxZ + 1) {
                val edge = x == minX - 1 || x == maxX + 1 || z == minZ - 1 || z == maxZ + 1
                setBlock(world, x, y + 4, z, if (edge) Blocks.OAK_SLAB else Blocks.OAK_PLANKS)
            }
        }
        setBlock(world, minX + 2, y + 1, maxZ - 1, Blocks.RED_BED)
        setBlock(world, maxX - 2, y + 1, maxZ - 1, Blocks.CRAFTING_TABLE)
        setBlock(world, maxX - 2, y + 1, minZ + 1, Blocks.CHEST)
    }

    private fun buildCastle(world: ServerWorld, plot: VillagePlotData) {
        val center = plot.center()
        val y = center.y
        val minX = center.x - 8
        val maxX = center.x + 8
        val minZ = center.z - 8
        val maxZ = center.z + 8
        clearBox(world, minX, y, minZ, maxX, y + 9, maxZ)

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                setBlock(world, x, y - 1, z, Blocks.STONE_BRICKS)
                if (x in minX + 1 until maxX && z in minZ + 1 until maxZ) {
                    setBlock(world, x, y, z, Blocks.SMOOTH_STONE)
                }
            }
        }

        for (level in 1..5) {
            for (x in minX..maxX) {
                setBlock(world, x, y + level, minZ, Blocks.STONE_BRICKS)
                setBlock(world, x, y + level, maxZ, Blocks.STONE_BRICKS)
            }
            for (z in minZ..maxZ) {
                setBlock(world, minX, y + level, z, Blocks.STONE_BRICKS)
                setBlock(world, maxX, y + level, z, Blocks.STONE_BRICKS)
            }
            buildTowerRing(world, minX, y + level, minZ)
            buildTowerRing(world, maxX, y + level, minZ)
            buildTowerRing(world, minX, y + level, maxZ)
            buildTowerRing(world, maxX, y + level, maxZ)
        }

        for (x in minX..maxX step 2) {
            setBlock(world, x, y + 6, minZ, Blocks.STONE_BRICK_WALL)
            setBlock(world, x, y + 6, maxZ, Blocks.STONE_BRICK_WALL)
        }
        for (z in minZ..maxZ step 2) {
            setBlock(world, minX, y + 6, z, Blocks.STONE_BRICK_WALL)
            setBlock(world, maxX, y + 6, z, Blocks.STONE_BRICK_WALL)
        }

        for (doorY in y + 1..y + 3) {
            setLocalBlock(world, center, -1, doorY, -8, plot.facing, Blocks.AIR)
            setLocalBlock(world, center, 0, doorY, -8, plot.facing, Blocks.AIR)
            setLocalBlock(world, center, 1, doorY, -8, plot.facing, Blocks.AIR)
        }
        setBlock(world, center.x, y + 1, center.z, Blocks.BELL)
    }

    private fun wallBlockFor(value: Int, min: Int, max: Int): Block {
        return if (value == min || value == max) Blocks.OAK_LOG else Blocks.OAK_PLANKS
    }

    private fun buildTowerRing(world: ServerWorld, centerX: Int, y: Int, centerZ: Int) {
        for (x in centerX - 1..centerX + 1) {
            for (z in centerZ - 1..centerZ + 1) {
                setBlock(world, x, y, z, Blocks.STONE_BRICKS)
            }
        }
    }

    private fun clearBox(world: ServerWorld, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) {
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    setBlock(world, x, y, z, Blocks.AIR, STRUCTURE_CLEAR_FLAGS)
                }
            }
        }
    }

    private fun setBlock(world: ServerWorld, x: Int, y: Int, z: Int, block: Block, flags: Int = Block.NOTIFY_ALL) {
        world.setBlockState(BlockPos(x, y, z), block.defaultState, flags)
    }

    private fun setLocalBlock(
        world: ServerWorld,
        center: BlockPos,
        localX: Int,
        y: Int,
        localZ: Int,
        facing: Direction,
        block: Block
    ) {
        val pos = localPos(center, localX, y, localZ, facing)
        setBlock(world, pos.x, pos.y, pos.z, block)
    }

    private fun localPos(center: BlockPos, localX: Int, y: Int, localZ: Int, facing: Direction): BlockPos {
        val (xOffset, zOffset) = when (facing) {
            Direction.NORTH -> localX to localZ
            Direction.SOUTH -> -localX to -localZ
            Direction.WEST -> localZ to -localX
            Direction.EAST -> -localZ to localX
            else -> localX to localZ
        }
        return BlockPos(center.x + xOffset, y, center.z + zOffset)
    }
}
