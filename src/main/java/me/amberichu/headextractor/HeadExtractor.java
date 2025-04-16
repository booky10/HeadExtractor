/*
 * Copyright (c) 2022-2023 Amberichu (davchoo).
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author Amberichu
 */

package me.amberichu.headextractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.steveice10.opennbt.NBTIO;
import com.github.steveice10.opennbt.tag.builtin.CompoundTag;
import com.github.steveice10.opennbt.tag.builtin.ListTag;
import com.github.steveice10.opennbt.tag.builtin.StringTag;
import com.github.steveice10.opennbt.tag.builtin.Tag;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class HeadExtractor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Please specify one world folder.");
            System.exit(-1);
        }
        Set<String> heads = extractHeads(Path.of(args[0]));
        heads.forEach(System.out::println);
    }

    private static Set<String> extractHeads(Path worldPath) throws IOException {
        Set<String> heads = ConcurrentHashMap.newKeySet();

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);
        List<CompletableFuture<?>> tasks = new ArrayList<>();

        Consumer<String> headConsumer = head -> {
            if (validateHead(head)) {
                heads.add(head);
            }
        };

        for (Path path : gatherMCA(worldPath)) {
            tasks.add(CompletableFuture.runAsync(() -> processMCA(path, headConsumer), executor));
        }
        for (Path path : gatherPlayerData(worldPath)) {
            tasks.add(CompletableFuture.runAsync(() -> processDAT(path, headConsumer), executor));
        }

        // Wait for all tasks to be complete
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        // Shut down the executor service to ensure the threads are killed
        executor.shutdownNow();

        return heads;
    }

    private static List<Path> gatherMCA(Path worldPath) throws IOException {
        Path entitiesPath = worldPath.resolve("entities");
        Path regionPath = worldPath.resolve("region");

        List<Path> mcaPaths = new ArrayList<>();
        if (Files.isDirectory(entitiesPath)) {
            try (Stream<Path> stream = Files.list(entitiesPath)) {
                stream.forEach(mcaPaths::add);
            }
        }
        if (Files.isDirectory(regionPath)) {
            try (Stream<Path> stream = Files.list(regionPath)) {
                stream.forEach(mcaPaths::add);
            }
        }
        mcaPaths.removeIf(path -> !Files.isRegularFile(path) || !path.getFileName().toString().endsWith("mca"));
        return mcaPaths;
    }

    private static List<Path> gatherPlayerData(Path worldPath) throws IOException {
        Path playerDataPath = worldPath.resolve("playerdata");
        Path levelDataPath = worldPath.resolve("level.dat");

        List<Path> dataPaths = new ArrayList<>();
        if (Files.isDirectory(playerDataPath)) {
            try (Stream<Path> stream = Files.list(playerDataPath)) {
                stream.forEach(dataPaths::add);
            }
        }
        dataPaths.add(levelDataPath);
        dataPaths.removeIf(path -> !Files.isRegularFile(path) || !path.getFileName().toString().endsWith("dat"));
        return dataPaths;
    }

    private static void processDAT(Path datPath, Consumer<String> headConsumer) {
        try {
            processTag(NBTIO.readFile(datPath.toFile()), headConsumer);
        } catch (IOException e) {
            System.err.println("Unable to fully process " + datPath + " due to exception: " + e);
        }
    }

    private static void processMCA(Path mcaPath, Consumer<String> headConsumer) {
        try (FileChannel channel = FileChannel.open(mcaPath, StandardOpenOption.READ)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            buffer.order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < 1024; i++) {
                int location = buffer.getInt(4 * i);
                if (location == 0) {
                    // Chunk is not present
                    continue;
                }

                int offset = ((location >> 8) & 0xFFFFFF) * 4096;
                int length = buffer.getInt(offset) - 1;
                byte compressionType = buffer.get(offset + 4);

                byte[] payload = new byte[length];
                buffer.get(offset + 5, payload);

                InputStream inputStream = new ByteArrayInputStream(payload);
                if (compressionType == 1) {
                    inputStream = new GZIPInputStream(inputStream);
                } else if (compressionType == 2) {
                    inputStream = new InflaterInputStream(inputStream);
                }
                inputStream = new BufferedInputStream(inputStream);
                processTag(NBTIO.readTag(inputStream), headConsumer);
            }
        } catch (IOException e) {
            System.err.println("Unable to fully process " + mcaPath + " due to exception: " + e);
        }
    }

    private static void processTag(Tag rootTag, Consumer<String> headConsumer) {
        Deque<Tag> tags = new ArrayDeque<>();
        tags.add(rootTag);
        while (!tags.isEmpty()) {
            Tag tag = tags.pop();
            if (tag instanceof CompoundTag compoundTag) {
                tags.addAll(compoundTag.values());
            } else if (tag instanceof ListTag listTag) {
                tags.addAll(listTag.getValue());
            } else if (tag instanceof StringTag stringTag) {
                headConsumer.accept(stringTag.getValue());
            }
        }
    }

    private static boolean validateHead(String head) {
        try {
            JsonNode node = MAPPER.readTree(Base64.getDecoder().decode(head));
            if (!node.isObject()) {
                return false;
            }

            JsonNode textures = node.get("textures");
            if (textures == null || !textures.isObject()) {
                return false;
            }

            JsonNode skin = textures.get("SKIN");
            if (skin == null || !textures.isObject()) {
                return false;
            }

            JsonNode url = skin.get("url");
            return url != null && url.isTextual();
        } catch (Exception e) {
            return false;
        }
    }
}
