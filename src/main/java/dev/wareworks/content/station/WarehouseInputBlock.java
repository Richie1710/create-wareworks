package dev.wareworks.content.station;

import com.mojang.serialization.MapCodec;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The warehouse input: items enter the warehouse here ({@code docs/warehouse-system.md} §3.2). Belts, funnels, chutes and
 * hoppers insert into its buffer; the stacker crane takes them out and stores them (M3).
 */
public class WarehouseInputBlock extends WarehouseStationBlock<WarehouseInputBlockEntity> {
    public static final MapCodec<WarehouseInputBlock> CODEC = simpleCodec(WarehouseInputBlock::new);

    public WarehouseInputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<WarehouseInputBlockEntity> getBlockEntityClass() {
        return WarehouseInputBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WarehouseInputBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.WAREHOUSE_INPUT.get();
    }
}
