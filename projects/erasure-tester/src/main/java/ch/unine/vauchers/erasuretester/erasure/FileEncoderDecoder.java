package ch.unine.vauchers.erasuretester.erasure;

import ch.unine.vauchers.erasuretester.backend.FileMetadata;
import ch.unine.vauchers.erasuretester.backend.StorageBackend;
import ch.unine.vauchers.erasuretester.erasure.codes.ErasureCode;
import ch.unine.vauchers.erasuretester.erasure.codes.SimpleRegeneratingCode;
import ch.unine.vauchers.erasuretester.erasure.codes.TooManyErasedLocations;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Intermediate layer between the frontend, the storage backend and erasure coding.
 */
public class FileEncoderDecoder {
    @NotNull
    protected final ErasureCode erasureCode;
    @NotNull
    protected final StorageBackend storageBackend;
    protected final Logger log = Logger.getLogger(FileEncoderDecoder.class.getName());
    protected final int totalSize;

    // Field used in decode/encode methods, declared globally for better performance
    protected final IntList erasedBlocksIndices;
    protected final int[] stripeBuffer;
    protected final int[] parityBuffer;
    protected final int[] dataBuffer;
    protected final int stripeSize;
    protected final int paritySize;

    private enum Modes {
        READ_FILE, WRITE_FILE
    }

    /**
     * Constructor
     * @param erasureCode The erasure coding implementation to use
     * @param storageBackend The storage backend implementation to use
     */
    public FileEncoderDecoder(@NotNull ErasureCode erasureCode, @NotNull StorageBackend storageBackend) {
        if (erasureCode instanceof SimpleRegeneratingCode && !(this instanceof SimpleRegeneratingFileEncoderDecoder)) {
            throw new IllegalArgumentException("SimpleRegeneratingCode needs a special FileEncoderDecoder");
        }

        this.erasureCode = erasureCode;
        this.storageBackend = storageBackend;

        // Only works with bytes
        assert(erasureCode.symbolSize() == 8);
        stripeSize = erasureCode.stripeSize();
        paritySize = erasureCode.paritySize();
        totalSize = stripeSize + paritySize;
        storageBackend.defineTotalSize(totalSize);

        erasedBlocksIndices = new IntArrayList();
        stripeBuffer = new int[stripeSize];
        parityBuffer = new int[paritySize];
        dataBuffer = new int[totalSize];
    }

    /**
     * Read a previously stored file from storage, and decode it
     * @param path String uniquely identifying a file
     * @param size How much bytes of contents to read
     * @param offset At which byte index the reading has to start
     * @param outBuffer The contents of the file will be written to this buffer. It needs to be previously allocated
     *                  with enough space to fit the size passed in parameter. Writing will start wherever the buffer
     *                  is positioned.
     * @throws TooManyErasedLocations Due to too many unavailable blocks, the contents can not be retrieved.
     */
    public void readFile(final String path, final int size, final int offset, @NotNull final ByteBuffer outBuffer) throws TooManyErasedLocations {
        log.info("Reading the file at " + path);

        final FileMetadata metadata = storageBackend.getFileMetadata(path)
                .orElseGet(() -> new FileMetadata().setContentsSize(0));
        final int contentsSize = Math.min(metadata.getContentsSize() - offset, size);
        if (contentsSize <= 0) {
            return;
        }
        final IntList allBlockKeys = metadata.getBlockKeys().get();

        iterate(contentsSize, offset, outBuffer, allBlockKeys, Modes.READ_FILE);
    }

    /**
     * Write the contents of a file to storage, with erasure coding applied
     * @param path String uniquely identifying the file
     * @param size How much bytes of contents to write
     * @param offset At which byte index do the writing starts (relative to the complete file)
     * @param contents The data to write into the file. The buffer will be read starting at its current position.
     */
    public void writeFile(String path, int size, int offset, @NotNull ByteBuffer contents) {
        log.info("Writing the file at " + path);

        final FileMetadata metadata = storageBackend.getFileMetadata(path).orElseGet(FileMetadata::new);
        final int iterationSize = Math.min(contents.limit(), size);
        final int oldContentSize = metadata.getContentsSize();
        final int contentsSize = Math.max(iterationSize + offset, oldContentSize);
        metadata.setContentsSize(contentsSize);
        IntArrayList blockKeys = (IntArrayList) metadata.getBlockKeys().orElseGet(IntArrayList::new);

        final int nextBoundary = nextBoundary(contentsSize);
        blockKeys.ensureCapacity(nextBoundary);

        for (int i = blockKeys.size(); i < nextBoundary; i++) {
            // Grow the blockKeys list to fit the size/offset given in parameter
            blockKeys.add(-1);
        }

        try {
            iterate(iterationSize, offset, contents, blockKeys, Modes.WRITE_FILE);
        } catch (TooManyErasedLocations ignored) {} // Will never happen

        metadata.setBlockKeys(blockKeys);
        storageBackend.setFileMetadata(path, metadata);
        storageBackend.flushAll();
    }

    /**
     * Return the size of a given file
     * @param path String uniquely identifying a file
     * @return The size of the contents of the file, in bytes
     */
    public int sizeOfFile(String path) {
        return storageBackend.getFileMetadata(path).orElse(FileMetadata.EMPTY_METADATA).getContentsSize();
    }

    /**
     * Truncate the size of a given file to a given size
     * @param filepath String uniquely identifying a file
     * @param size The new size of the file
     */
    public void truncate(final String filepath, final int size) {
        storageBackend.getFileMetadata(filepath).ifPresent(metadata -> {
            final int newSize = Math.min(metadata.getContentsSize(), size);
            metadata.setContentsSize(newSize);
            metadata.getBlockKeys().ifPresent(integers -> integers.size(newSize));
        });
    }

    /**
     * Repair a file. Checks the availability of all blocks of the file from the key-value store. All unavailable blocks
     * are repaired using other available blocks, when possible. After the operation is completed, the file should have
     * all its blocks in an intact state.
     * @param path The path of the file to repair
     */
    public void repairFile(String path) {
        storageBackend.getFileMetadata(path).ifPresent(this::repairFile);
    }

    protected void repairFile(FileMetadata metadata) {
        metadata.getBlockKeys().ifPresent(blockKeys -> {
            final int nbKeys = blockKeys.size();
            assert nbKeys % totalSize == 0;
            final int nbStripes = nbKeys / totalSize;

            for (int i = 0; i < nbStripes; i++) {
                final int offset = i * totalSize;
                final IntList subKeys = blockKeys.subList(offset, offset + totalSize);
                final IntList availableBlocks = new IntArrayList(totalSize);
                for (int j = 0; j < totalSize; j++) {
                    if (storageBackend.isBlockAvailable(subKeys.getInt(j))) {
                        availableBlocks.add(j);
                    }
                }
                if (availableBlocks.size() < totalSize) {
                    final IntList erasedBlocks = IntArrayList.wrap(fillNotToRead(availableBlocks));
                    try {
                        final IntList toReadForDecode = erasureCode.locationsToReadForDecode(erasedBlocks);
                        toReadForDecode.sort(null);
                        Arrays.fill(dataBuffer, 0);
                        for (Integer position : toReadForDecode) {
                            dataBuffer[position] = storageBackend.retrieveBlock(subKeys.getInt(position)).orElse(0);
                        }
                        final int[] erasedValues = new int[erasedBlocks.size()];
                        erasureCode.decode(dataBuffer, erasedBlocks.toIntArray(), erasedValues, toReadForDecode.toIntArray(), fillNotToRead(toReadForDecode));
                        for (int j = 0; j < erasedValues.length; j++) {
                            final int position = erasedBlocks.get(j);
                            final int restoredValue = erasedValues[j];
                            final int blockKey = storageBackend.storeBlock(restoredValue, position);
                            subKeys.set(position, blockKey);
                        }
                    } catch (TooManyErasedLocations e) {
                        // log.warning("The file cannot be repaired");
                    }
                }
            }

            storageBackend.flushAll();
        });
    }

    /**
     * Perform a repair operation on all files stored in the system.
     */
    public void repairAllFiles() {
        final Collection<String> filePaths = storageBackend.getAllFilePaths();
        filePaths.stream()
                .map(storageBackend::getFileMetadata)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(this::repairFile);
    }

    /**
     * Internal operation. Correctly chunk the file.
     * @param size The size of the read/write operation
     * @param offset The offset in the fileBuffer
     * @param fileBuffer The buffer to read from/write to
     * @param blockKeys The list of all block keys related to the file
     * @param mode Read or Write
     * @throws TooManyErasedLocations
     */
    private void iterate(int size, int offset, ByteBuffer fileBuffer, IntList blockKeys, Modes mode) throws TooManyErasedLocations {
        final int firstLowerBoundary = previousBoundary(offset);
        final int lastLowerBoundary = previousBoundary(offset + size);
        final int blocksToDiscardBeginning = lowerBytesToDrop(offset);
        final int blocksToDiscardEnd = higherBytesToDrop(offset + size);

        for (int i = firstLowerBoundary; i <= lastLowerBoundary; i += totalSize) {
            int offsetPart = 0;
            int sizePart = stripeSize;
            if (i == firstLowerBoundary) {
                offsetPart += blocksToDiscardBeginning;
                sizePart -= blocksToDiscardBeginning;
            } else if (i == lastLowerBoundary) {
                if (blocksToDiscardEnd == 0) {
                    break;
                } else {
                    sizePart -= blocksToDiscardEnd;
                }
            }

            ByteBuffer buffer = fileBuffer.slice();
            if (i < lastLowerBoundary) {
                fileBuffer.position(fileBuffer.position() + sizePart);
            }
            final IntList keysSublist = blockKeys.subList(i, i + totalSize);
            if (mode == Modes.READ_FILE) {
                readPart(keysSublist, buffer, sizePart, offsetPart);
            } else if (mode == Modes.WRITE_FILE) {
                writePart(keysSublist, buffer, sizePart, offsetPart);
            }
        }
    }

    private synchronized void readPart(IntList blockKeys, ByteBuffer outBuffer, int size, int offset) throws TooManyErasedLocations {
        erasedBlocksIndices.clear();

        final Iterator<Boolean> blocksAvailableIterator = blockKeys.stream().map(storageBackend::isBlockAvailable).iterator();
        for (int i = 0; i < totalSize; i++) {
            if (!blocksAvailableIterator.next()) {
                erasedBlocksIndices.add(i);
            }
        }

        final Stream<Byte> partData = decodeFileData(blockKeys, erasedBlocksIndices);

        partData.skip(offset).limit(size).forEachOrdered(outBuffer::put);
    }

    private synchronized void writePart(IntList blockKeys, ByteBuffer fileBuffer, int size, int offset) {
        for (int i = 0; i < stripeSize; i++) {
            if (i < offset || i >= offset + size) { // Restore existing data
                final int key = blockKeys.getInt(i + paritySize);
                if (key != -1) {
                    stripeBuffer[i] = storageBackend.retrieveBlock(key).orElse(0);
                } else {
                    stripeBuffer[i] = 0;
                }
            } else if (fileBuffer.hasRemaining()) {
                stripeBuffer[i] = Byte.toUnsignedInt(fileBuffer.get());
            }
        }

        erasureCode.encode(stripeBuffer, parityBuffer);

        for (int i = 0; i < paritySize; i++) {
            int key = storageBackend.storeBlock(parityBuffer[i], i);
            blockKeys.set(i, key);
        }
        for (int i = 0; i < stripeSize; i++) {
            int key = storageBackend.storeBlock(stripeBuffer[i], i + paritySize);
            blockKeys.set(i + paritySize, key);
        }
    }

    protected Stream<Byte> decodeFileData(IntList blockKeys, IntList erasedIndices) throws TooManyErasedLocations {
        IntList toReadForDecode;
        boolean retry;
        do {
            retry = false;
            toReadForDecode = erasureCode.locationsToReadForDecode(erasedIndices);
            toReadForDecode.sort(null);

            for (int index : toReadForDecode) {
                final int key = blockKeys.getInt(index);
                Optional<Integer> block = storageBackend.retrieveBlock(key);
                if (block.isPresent()) {
                    dataBuffer[index] = block.get();
                } else {
                    erasedIndices.add(index);
                    retry = true;
                    break;
                }
            }
        } while (retry);

        int[] indicesToRecover = erasedIndices.parallelStream().filter(index -> index >= paritySize).mapToInt(Integer::intValue).toArray();

        final int[] recoveredValues = new int[indicesToRecover.length];
        erasureCode.decode(dataBuffer, indicesToRecover, recoveredValues, convertToIntArray(toReadForDecode), fillNotToRead(toReadForDecode));

        // Restore erased values
        for (int i = 0; i < recoveredValues.length; i++) {
            dataBuffer[indicesToRecover[i]] = recoveredValues[i];
        }

        return Arrays.stream(dataBuffer).skip(paritySize).boxed().map(Integer::byteValue);
    }

    protected int[] fillNotToRead(IntList toReadForDecode) {
        final int trSize = toReadForDecode.size();
        int[] notToRead = new int[totalSize - trSize];

        int ntrIndex = 0;
        int trIndex = 0;
        for (int globIndex = 0; globIndex < totalSize; globIndex++) {
            if (trIndex < trSize && toReadForDecode.getInt(trIndex) == globIndex) {
                trIndex++;
            } else {
                notToRead[ntrIndex++] = globIndex;
            }
        }

        return notToRead;
    }

    private static int[] convertToIntArray(IntList integerCollection) {
        return integerCollection.toIntArray();
    }

    int nextBoundary(int index) {
        return computeBoundary(Math::ceil, index);
    }

    int previousBoundary(int index) {
        return computeBoundary(Math::floor, index);
    }

    private int computeBoundary(Function<Double, Double> mathFunction, int index) {
        return (int) Math.round(mathFunction.apply(index / (double) stripeSize) * totalSize);
    }

    int lowerBytesToDrop(int index) {
        return index % stripeSize;
    }

    int higherBytesToDrop(int index) {
        final int lowerBytesToDrop = lowerBytesToDrop(index);
        if (index == 0) {
            return stripeSize;
        } else if (lowerBytesToDrop == 0) {
            return 0;
        }
        return stripeSize - lowerBytesToDrop;
    }

    @Override
    public String toString() {
        return "FileEncoderDecoder{" +
                "storageBackend=" + storageBackend +
                ", erasureCode=" + erasureCode +
                '}';
    }
}
