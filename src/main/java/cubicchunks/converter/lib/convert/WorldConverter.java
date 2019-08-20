/*
 *  This file is part of CubicChunksConverter, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2017 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.converter.lib.convert;

import cubicchunks.converter.lib.IProgressListener;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WorldConverter<IN, OUT> {

    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int CONVERT_QUEUE_SIZE = 64 * THREADS;
    private static final int IO_QUEUE_SIZE = 32 * THREADS;

    private final LevelInfoConverter<IN, OUT> levelConverter;
    private final ChunkDataReader<IN> reader;
    private final ChunkDataConverter<IN, OUT> converter;
    private final ChunkDataWriter<OUT> writer;

    private final AtomicInteger chunkCount;
    private int copyChunks;

    private final ArrayBlockingQueue<Runnable> convertQueueImpl;
    private final ArrayBlockingQueue<Runnable> ioQueueImpl;

    private final ThreadPoolExecutor convertQueue;
    private final ThreadPoolExecutor ioQueue;

    private volatile boolean discardConverted = false;
    private volatile boolean errored = false;
    // handle errors one at a time
    private final Object errorLock = new Object();

    public WorldConverter(
        LevelInfoConverter<IN, OUT> levelConverter,
        ChunkDataReader<IN> reader,
        ChunkDataConverter<IN, OUT> converter,
        ChunkDataWriter<OUT> writer) {

        this.levelConverter = levelConverter;
        this.reader = reader;
        this.converter = converter;
        this.writer = writer;

        RejectedExecutionHandler handler = ((r, executor) -> {
            try {
                if (!executor.isShutdown()) {
                    executor.getQueue().put(r);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Executor was interrupted while the task was waiting to put on work queue", e);
            }
        });

        chunkCount = new AtomicInteger(0);

        convertQueueImpl = new ArrayBlockingQueue<>(CONVERT_QUEUE_SIZE);
        convertQueue = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, convertQueueImpl);
        convertQueue.setRejectedExecutionHandler(handler);

        ioQueueImpl = new ArrayBlockingQueue<>(IO_QUEUE_SIZE);
        ioQueue = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, ioQueueImpl);
        ioQueue.setRejectedExecutionHandler(handler);
    }

    public void convert(IProgressListener progress) throws IOException {
        startCounting();

        try {
            reader.loadChunks(inData -> {
                convertQueue.submit(new ChunkConvertTask<>(converter, writer, progress, this, ioQueue, inData));
                copyChunks++;
            });
        } catch (InterruptedException e) {
            // just shutdown
        } finally {
            convertQueue.shutdown();
            boolean shutdownNow = false;
            try {
                convertQueue.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
                convertQueue.shutdownNow();
                shutdownNow = true;
            }
            // convert finished, now shut down IO
            if (shutdownNow) {
                ioQueue.shutdownNow();
            } else {
                ioQueue.shutdown();
            }

            try {
                ioQueue.awaitTermination(Long.MAX_VALUE / 2, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
                ioQueue.shutdownNow();
            }
            try {
                reader.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                writer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (discardConverted) {
                try {
                    writer.discardData();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
        if (!errored) {
            levelConverter.convert();
        }
    }

    public int getSubmittedChunks() {
        return copyChunks;
    }

    public int getTotalChunks() {
        return chunkCount.get();
    }

    public int getConvertBufferFill() {
        return convertQueueImpl.size();
    }

    public int getConvertBufferMaxSize() {
        return CONVERT_QUEUE_SIZE;
    }

    public int getIOBufferFill() {
        return ioQueueImpl.size();
    }

    public int getIOBufferMaxSize() {
        return IO_QUEUE_SIZE;
    }

    private void startCounting() {
        new Thread(() -> {
            try {
                reader.countInputChunks(chunkCount::getAndIncrement);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // stop
            }
        }, "Chunk and File counting thread").start();
    }

    private void handleError(Throwable t, IProgressListener progress) {
        synchronized (errorLock) {
            if (errored) {
                return;
            }
            IProgressListener.ErrorHandleResult result = progress.error(t);
            switch (result) {
                case STOP_DISCARD:
                    discardConverted = true;
                    // fallthrough
                case STOP_KEEP_DATA:
                    reader.stop();
                    convertQueue.shutdownNow();
                    ioQueue.shutdownNow();
                    // fallthrough
                case IGNORE_ALL:
                    errored = true;
            }
        }
    }

    private static class ChunkConvertTask<IN, OUT> implements Callable<Void> {
        private final ChunkDataConverter<IN, OUT> converter;
        private final ChunkDataWriter<OUT> writer;
        private final IProgressListener progress;
        private WorldConverter<IN, OUT> worldConv;
        private final ThreadPoolExecutor ioExecutor;
        private final IN toConvert;

        ChunkConvertTask(
            ChunkDataConverter<IN, OUT> converter,
            ChunkDataWriter<OUT> writer,
            IProgressListener progress,
            WorldConverter<IN, OUT> worldConv,
            ThreadPoolExecutor ioExecutor,
            IN toConvert) {

            this.converter = converter;
            this.writer = writer;
            this.progress = progress;
            this.worldConv = worldConv;
            this.ioExecutor = ioExecutor;
            this.toConvert = toConvert;
        }

        @Override public Void call() {
            try {
                OUT converted = converter.convert(toConvert);
                IOWriteTask<OUT> data = new IOWriteTask<>(converted, writer, worldConv, progress);
                progress.update(null);
                ioExecutor.submit(data);
            } catch (Throwable t) {
                worldConv.handleError(t, progress);
            }
            return null;
        }
    }

    private static class IOWriteTask<OUT> implements Callable<Void> {

        private final OUT toWrite;
        private final ChunkDataWriter<OUT> writer;
        private final WorldConverter worldConv;
        private final IProgressListener progress;

        IOWriteTask(OUT toWrite, ChunkDataWriter<OUT> writer, WorldConverter worldConv, IProgressListener progress) {
            this.toWrite = toWrite;
            this.writer = writer;
            this.worldConv = worldConv;
            this.progress = progress;
        }

        @Override public Void call() {
            try {
                writer.accept(toWrite);
            } catch (Throwable t) {
                worldConv.handleError(t, progress);
            }
            return null;
        }
    }
}