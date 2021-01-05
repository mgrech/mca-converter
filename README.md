# mca-converter
Converts Minecraft's .mca files to a form that is easier to work with. Note however, that only block data is exported: No lighting, biome information, entities or anything else.

## Usage
`java -jar mca-converter.jar <version> <region-dir> <output-dir>`
- `<version>`: Minecraft version of the save-files. A [blocks report](https://wiki.vg/Data_Generators#Blocks_report) is required to be present in the `blocks/` directory for the specified version.
- `<region-dir>`: Path to directory containing the region files.
- `<output-dir>`: Path to a directory where the converted files should be saved.

## Output Format
The resulting .bin files are a 1:1 mapping from the original .mca region files and contain the following structure:

```
struct Region
{
	uint8[128] chunkMask;
	Chunk[] chunks;
}

struct Chunk
{
	uint16 sectionMask;
	Section[] sections;
}

struct Section
{
	uint16[16*16*16] blocks;
}
```
- The `chunkMask` contains a 1-bit for every chunk that is present in the region. Bits are in ZX-order.
- Next is an array of all the chunks where the bitmask contained a 1-bit.
- Each chunk has a `sectionMask`, which specifies which of the 16 sections are present in the chunk.
- For each 1-bit in the `sectionMask` a section follows, starting with the bottom-most section going upwards.
- Finally, each section consists of an array of blocks in YZX order, where each block is stored as a 16-bit integer.

All data is in little-endian byte-order.
