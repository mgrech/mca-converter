import com.fasterxml.jackson.databind.ObjectMapper;
import net.querz.mca.LoadFlags;
import net.querz.mca.MCAUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class Main
{
	private static final int CHUNKS_PER_REGION = 32 * 32;
	private static final int CHUNK_MASK_LENGTH = CHUNKS_PER_REGION / 8;
	private static final int SECTIONS_PER_CHUNK = 16;
	private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
	private static final int BLOCK_SIZE = 2;
	private static final int SECTION_SIZE = BLOCK_SIZE * BLOCKS_PER_SECTION;
	private static final int SECTION_MASK_SIZE = 2;
	private static final int MAX_CHUNK_SIZE = SECTION_MASK_SIZE + 16 * SECTION_SIZE;

	private static final ByteBuffer WRITE_BUFFER = ByteBuffer.allocate(MAX_CHUNK_SIZE);
	private static final ShortBuffer SHORT_BUFFER = WRITE_BUFFER.order(ByteOrder.LITTLE_ENDIAN)
	                                                            .asShortBuffer();

	private static String blockStateToString(CompoundTag blockState)
	{
		var name = ((StringTag)blockState.get("Name")).getValue();
		var builder = new StringBuilder(name);

		if(blockState.containsKey("Properties"))
		{
			var props = (CompoundTag)blockState.get("Properties");
			var sortedProps = new TreeMap<String, String>();

			for(var prop : props)
				sortedProps.put(prop.getKey(), ((StringTag)prop.getValue()).getValue());

			for(var prop : sortedProps.entrySet())
			{
				builder.append(' ');
				builder.append(prop.getKey());
				builder.append('=');
				builder.append(prop.getValue());
			}
		}

		return builder.toString();
	}

	private static int[] translatePalette(ListTag<CompoundTag> palette, Map<String, Integer> blockStates)
	{
		var result = new int[palette.size()];

		for(var i = 0; i != palette.size(); ++i)
		{
			var state = blockStateToString(palette.get(i));
			result[i] = blockStates.get(state);
		}

		return result;
	}

	private static void convertRegion(Path path, Map<String, Integer> blockStates, Path outputDir) throws IOException
	{
		var parts = path.getFileName().toString().split("\\.");
		var regionX = Integer.parseInt(parts[1]);
		var regionZ = Integer.parseInt(parts[2]);
		var region = MCAUtil.read(path.toFile(), LoadFlags.BLOCK_STATES);
		var fileName = String.format("%s.%s.bin", regionX, regionZ);

		try(var file = new RandomAccessFile(outputDir.resolve(fileName).toFile(), "rw"))
		{
			file.seek(CHUNK_MASK_LENGTH);
			var chunkMask = new byte[CHUNK_MASK_LENGTH];

			for(var chunkIndex = 0; chunkIndex != CHUNKS_PER_REGION; ++chunkIndex)
			{
				var chunk = region.getChunk(chunkIndex);

				if(chunk == null)
					continue;

				short sectionMask = 0;
				var sectionCount = 0;

				for(var sectionY = 0; sectionY != SECTIONS_PER_CHUNK; ++sectionY)
				{
					var section = chunk.getSection(sectionY);

					// skip empty sections
					if(section == null || section.getPalette() == null)
						continue;

					var palette = translatePalette(section.getPalette(), blockStates);

					// sometimes minecraft stores empty sections explicitly as all-air sections,
					// e.g. if lighting data spans across the section boundary. skip them.
					if(palette.length == 1 && palette[0] == blockStates.get("minecraft:air"))
						continue;

					sectionMask |= 1 << sectionY;

					for(var blockIndex = 0; blockIndex != BLOCKS_PER_SECTION; ++blockIndex)
					{
						var paletteIndex = section.getPaletteIndex(blockIndex);
						var state = palette[paletteIndex];
						var writeIndex = 1 + sectionCount * BLOCKS_PER_SECTION + blockIndex;
						SHORT_BUFFER.put(writeIndex, (short)state);
					}

					++sectionCount;
				}

				if(sectionCount > 0)
				{
					SHORT_BUFFER.put(0, sectionMask);

					WRITE_BUFFER.limit(SECTION_MASK_SIZE + sectionCount * SECTION_SIZE);
					file.write(WRITE_BUFFER.array(), 0, WRITE_BUFFER.limit());

					chunkMask[chunkIndex / 8] |= 1 << (chunkIndex % 8);
				}
			}

			file.seek(0);
			file.write(chunkMask);
		}
	}

	private static Map<String, Integer> loadBlockStates(Path path) throws IOException
	{
		var report = new ObjectMapper().readTree(path.toFile());
		var idsByState = new HashMap<String, Integer>();

		for(var it = report.fields(); it.hasNext();)
		{
			var elem = it.next();
			var name = elem.getKey();
			var data = elem.getValue();
			var states = data.get("states");

			if(!data.has("properties"))
			{
				if(states.size() > 1)
					throw new RuntimeException("block type without properties should not have multiple states");

				idsByState.put(name, states.get(0).get("id").asInt());
			}
			else
			{
				for(var state : states)
				{
					var builder = new StringBuilder(name);
					var props = state.get("properties");

					for(var propIter = props.fields(); propIter.hasNext();)
					{
						var property = propIter.next();
						builder.append(' ');
						builder.append(property.getKey());
						builder.append('=');
						builder.append(property.getValue().asText());
					}

					var id = state.get("id").asInt();
					idsByState.put(builder.toString(), id);
				}
			}
		}

		return idsByState;
	}

	public static void main(String[] args) throws IOException
	{
		if(args.length != 3)
		{
			System.err.println("invalid args, expected <version> <region-dir> <output-dir>");
			System.exit(1);
		}

		var version = args[0];
		var regionDir = Paths.get(args[1]);
		var outputDir = Paths.get(args[2]);

		var blockStates = loadBlockStates(Paths.get(String.format("blocks/%s.json", version)));

		Files.list(regionDir)
		     .filter(p -> p.getFileName().toString().matches("r\\.[-]?\\d+\\.[-]?\\d+\\.mca"))
		     .forEach(path ->
		              {
			              try
			              {
				              convertRegion(path, blockStates, outputDir);
			              }
			              catch(EOFException ex)
			              {
				              System.err.printf("warning: could not parse region file %s, skipping\n", path.getFileName());
			              }
			              catch(IOException ex)
			              {
				              throw new RuntimeException(ex);
			              }
		              });
	}
}
