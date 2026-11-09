package qouteall.imm_ptl.peripheral.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/** A reference only: paid identity, ownership and offerings live in the server ledger. */
public final class RealmCoreBlockEntity extends BlockEntity {
    private UUID realmId;

    public RealmCoreBlockEntity(BlockPos pos, BlockState state) {
        super(Riftwoven.CORE_ENTITY, pos, state);
    }

    public UUID realmId() { return realmId; }

    public void bind(UUID id) {
        realmId = id;
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        realmId = parse(input.getStringOr("realm", ""));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (realmId != null) output.putString("realm", realmId.toString());
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (realmId != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("riftwoven_realm", realmId.toString());
            builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        CustomData data = components.get(DataComponents.CUSTOM_DATA);
        if (data != null) realmId = parse(data.copyTag().getStringOr("riftwoven_realm", ""));
    }

    private static UUID parse(String text) {
        try { return text.isEmpty() ? null : UUID.fromString(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
