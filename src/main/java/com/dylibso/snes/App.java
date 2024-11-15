package com.dylibso.snes;

import com.dylibso.chicory.experimental.aot.AotMachineFactory;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasm.types.ValueType;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.experimental.aot.AotMachine;

import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

class SNESFrameRenderer extends JPanel {
    private BufferedImage image;
    private int[] imageData;

    public SNESFrameRenderer(int width, int height) {
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    public void updateFrame(byte[] buffer) {
        System.out.println(buffer.length);
        for (int i = 0, j = 0; i < imageData.length; i++, j += 2) {
            int rgb565 = ((buffer[j + 1] & 0xFF) << 8) | (buffer[j] & 0xFF);
            // Get channels
            int red = (rgb565 >> 11) & 0x1F;
            int green = (rgb565 >> 5) & 0x3F;
            int blue = rgb565 & 0x1F;
            // Convert to 8-bit values
            red = (red << 3) | (red >> 2);
            green = (green << 2) | (green >> 4);
            blue = (blue << 3) | (blue >> 2);

            imageData[i] = (0xFF << 24) | (red << 16) | (green << 8) | blue;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        // Enable antialiasing for better image quality
        //g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Draw the scaled image
        g2d.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), null);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(256, 224);
    }
}

public class App
{
    public static void main(String[] cliArgs) throws IOException, InterruptedException {

        var fdWrite = new HostFunction(
                "a",
                "a",
                List.of(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
                List.of(ValueType.I32),
                (Instance instance, long... args) -> {
                    System.out.println("fdWrite: " + Arrays.toString(args));
                    return null;
                });

        var resizeHeap = new HostFunction(
                "a",
                "b",
                List.of(ValueType.I32),
                List.of(ValueType.I32),
                (Instance instance, long... args) -> {
                    System.out.println("resizeHeap: " + Arrays.toString(args));
                    return null;
                });

        var dateNow = new HostFunction(
                "a",
                "c",
                List.of(),
                List.of(ValueType.F64),
                (Instance instance, long... args) -> {
                    System.out.println("dateNow: " + Arrays.toString(args));
                    return null;
                });

        var memcpy = new HostFunction(
                "a",
                "d",
                List.of(ValueType.I32, ValueType.I32, ValueType.I32),
                List.of(),
            (Instance instance, long... args) -> {
                System.out.println("Memcpy: " + Arrays.toString(args));
                var dest = (int) args[0];
                var src = (int) args[1];
                var size = (int) args[2];
                instance.memory().copy(dest, src, size);
                return null;
            });

//        var store = new Store();
//        store.addFunction(fdWrite, resizeHeap, dateNow, memcpy);
//        var is = App.class.getResourceAsStream("/snes9x_2005-raw.wasm");
//        var module = Parser.parse(is);
//        Instance instance = store.instantiate("snes", module);

        var imports = ImportValues.builder().withFunctions(List.of(memcpy, dateNow, resizeHeap, fdWrite)).build();

        var is = App.class.getResourceAsStream("/snes9x_2005-raw.wasm");
        var module = Parser.parse(is);
        var instance = Instance.builder(module).withImportValues(imports).withMachineFactory(AotMachine::new).build();

        var callCtors = instance.export("f");
        var setJoypadInput = instance.export("h");
        var malloc = instance.export("i");
        var free = instance.export("j");
        var startWithRom = instance.export("k");
        var mainLoop = instance.export("l");
        var getScreenBuffer = instance.export("m");
        var getSoundBuffer = instance.export("n");
        var saveSramRequest = instance.export("o");
        var getSaveSramSize = instance.export("p");
        var getSaveSram = instance.export("q");
        var loadSram = instance.export("r");
        var getSaveStateSize = instance.export("s");
        var saveState = instance.export("t");
        var loadState = instance.export("u");

        // init the runtime
        callCtors.apply();

        // TODO change to your rom
        //var romPath = Paths.get("/Users/ben/Super Mario World.smc");
        var romPath = Paths.get("/Users/ben/The Legend of Zelda - A Link to the Past.smc");
        var romBytes = Files.readAllBytes(romPath);
        var romLen = (long)romBytes.length;
        var romPtr = malloc.apply(romLen)[0];
        instance.memory().write((int)romPtr, romBytes);
        var sampleRate = 36000L;
        startWithRom.apply(romPtr, romLen, sampleRate);
        free.apply(romPtr);

        // Create a JFrame to display the image
        JFrame frameWindow = new JFrame("SNES Frame Renderer");
        frameWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frameWindow.setSize(512, 448);

        var renderer = new SNESFrameRenderer(512, 448);
        frameWindow.add(renderer);
        frameWindow.pack();
        frameWindow.setVisible(true);

        long framePtr = 0;

        while (true) {
            setJoypadInput.apply(0L);
            long startTime = System.nanoTime();
            mainLoop.apply();
            System.out.println("Main Loop: " + (System.nanoTime() - startTime) + "ns");
            startTime = System.currentTimeMillis();
            framePtr = getScreenBuffer.apply()[0];
            var frame = instance.memory().readBytes((int)framePtr, 512 * 448 * 2);
            renderer.updateFrame(frame);
            System.out.println("Render Frame: " + (System.currentTimeMillis() - startTime) + "ms");
            System.out.println("======================");
            System.out.println(frame[(int) Math.round(Math.random() * 1000)]);
        }
    }
}
