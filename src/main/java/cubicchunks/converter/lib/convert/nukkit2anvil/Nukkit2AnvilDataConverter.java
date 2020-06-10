package cubicchunks.converter.lib.convert.nukkit2anvil;

import cn.nukkit.block.BlockID;
import com.flowpowered.nbt.ByteArrayTag;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.ListTag;
import cubicchunks.converter.lib.convert.ChunkDataConverter;
import cubicchunks.converter.lib.convert.data.AnvilChunkData;
import cubicchunks.converter.lib.convert.data.NukkitChunkData;
import cubicchunks.converter.lib.util.NibbleArray;
import cubicchunks.converter.lib.util.Utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * @author DaPorkchop_
 */
public class Nukkit2AnvilDataConverter implements ChunkDataConverter<NukkitChunkData, AnvilChunkData> {
    private static int id(int block, int meta) {
        return (block << 4) | meta;
    }

    private static int keepMeta(int id, int newBlock) {
        return (id & 0xF) | (newBlock << 4);
    }

    private static int keepId(int id, int newMeta) {
        return (id & ~0xF) | newMeta;
    }

    private static int fixId(int id) {
        switch (id >> 4) {
            case BlockID.PODZOL:
                return id(3, 2);
            case BlockID.DOUBLE_WOODEN_SLAB:
                return keepMeta(id, 125);
            case BlockID.WOOD_SLAB:
                return keepMeta(id, 126);
            case 241:
                return keepMeta(id, 95);
            case 126:
                return keepMeta(id, 157);
            case 125:
                return keepMeta(id, 158);
            case BlockID.GLASS_PANE:
                return keepMeta(id, 160);
            case BlockID.INVISIBLE_BEDROCK:
                return id(166, 0);
            case BlockID.FENCE:
                switch (id & 0xF) {
                    case 1:
                        return id(188, 0);
                    case 2:
                        return id(189, 0);
                    case 3:
                        return id(190, 0);
                    case 4:
                        return id(191, 0);
                    case 5:
                        return id(192, 0);
                    default:
                        return id(85, 0);
                }
            case BlockID.END_ROD:
                return keepMeta(id, 198);
            case BlockID.CHORUS_PLANT:
                return keepMeta(id, 199);
            case BlockID.BEETROOT_BLOCK:
                return keepMeta(id, 207);
            case BlockID.GRASS_PATH:
                return keepMeta(id, 208);
            case 188:
                return keepMeta(id, 210);
            case 189:
                return keepMeta(id, 211);
            case BlockID.OBSERVER:
                return keepMeta(id, 218);
            case BlockID.WHITE_GLAZED_TERRACOTTA:
                return keepMeta(id, 235);
            case BlockID.ORANGE_GLAZED_TERRACOTTA:
                return keepMeta(id, 236);
            case BlockID.MAGENTA_GLAZED_TERRACOTTA:
                return keepMeta(id, 237);
            case BlockID.LIGHT_BLUE_GLAZED_TERRACOTTA:
                return keepMeta(id, 238);
            case BlockID.YELLOW_GLAZED_TERRACOTTA:
                return keepMeta(id, 239);
            case BlockID.LIME_GLAZED_TERRACOTTA:
                return keepMeta(id, 240);
            case BlockID.PINK_GLAZED_TERRACOTTA:
                return keepMeta(id, 241);
            case BlockID.GRAY_GLAZED_TERRACOTTA:
                return keepMeta(id, 242);
            case BlockID.SILVER_GLAZED_TERRACOTTA:
                return keepMeta(id, 243);
            case BlockID.CYAN_GLAZED_TERRACOTTA:
                return keepMeta(id, 244);
            case BlockID.PURPLE_GLAZED_TERRACOTTA:
                return keepMeta(id, 245);
            case BlockID.BLUE_GLAZED_TERRACOTTA:
                return keepMeta(id, 246);
            case BlockID.BROWN_GLAZED_TERRACOTTA:
                return keepMeta(id, 247);
            case BlockID.GREEN_GLAZED_TERRACOTTA:
                return keepMeta(id, 248);
            case BlockID.RED_GLAZED_TERRACOTTA:
                return keepMeta(id, 249);
            case BlockID.BLACK_GLAZED_TERRACOTTA:
                return keepMeta(id, 250);
            case BlockID.CONCRETE:
                return keepMeta(id, 251);
            case BlockID.CONCRETE_POWDER:
                return keepMeta(id, 252);
            case BlockID.STONE_BUTTON:
            case BlockID.WOODEN_BUTTON: {
                int face = id & 0x7;
                switch (face) {
                    case 0:
                        face = 0;
                        break;
                    case 5:
                        face = 1;
                        break;
                    case 4:
                        face = 2;
                        break;
                    case 3:
                        face = 3;
                        break;
                    case 2:
                        face = 4;
                        break;
                    case 1:
                        face = 5;
                        break;
                    default:
                        face = 0;
                }
                return keepId(id, face | (id & 0x8));
            }
            case BlockID.SHULKER_BOX:
                return id(219 + (id & 0xF), 0);
        }
        return id;
    }

    private static int getAnvilIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int fixSection(CompoundTag section) {
        byte[] blocks = ((ByteArrayTag) section.getValue().get("Blocks")).getValue();
        byte[] data = ((ByteArrayTag) section.getValue().get("Data")).getValue();
        NibbleArray meta = new NibbleArray(data);

        int changed = 0;
        for (int y = 0; y < 16; y++)    {
            for (int z = 0; z < 16; z++)    {
                for (int x = 0; x < 16; x++)    { //minimize cache misses
                    int index = getAnvilIndex(x, y, z);
                    int oldId = ((blocks[index] & 0xFF) << 4) | meta.get(index);
                    int newId = fixId(oldId);
                    if (newId != oldId) {
                        blocks[index] = (byte) (newId >> 4);
                        meta.set(index, newId & 0xF);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AnvilChunkData convert(NukkitChunkData input) {
        try {
            CompoundTag tag = Utils.readCompressed(new ByteArrayInputStream(input.getData().array()));
            boolean dirty = ((ListTag<CompoundTag>) ((CompoundTag) tag.getValue().get("Level")).getValue().get("Sections")).getValue().stream()
                    .mapToInt(Nukkit2AnvilDataConverter::fixSection)
                    .max().orElse(0) != 0;
            return new AnvilChunkData(input.getDimension(), input.getPosition(), dirty ? Utils.writeCompressed(tag, true) : input.getData());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
