package com.dylibso.snes;

import com.dylibso.chicory.aot.AotMachine;
import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.HostImports;
import com.dylibso.chicory.wasm.types.ValueType;
import com.dylibso.chicory.wasm.types.Value;
import com.dylibso.chicory.runtime.Module;
import com.dylibso.chicory.runtime.Instance;

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
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Calculate the scaling factor to fit the panel
        int panelWidth = getWidth();
        int panelHeight = getHeight();
//        double scaleX = (double) panelWidth / image.getWidth();
//        double scaleY = (double) panelHeight / image.getHeight();
//        double scale = Math.min(scaleX, scaleY); // Maintain aspect ratio

        // Calculate the top-left corner for centering the image
        int x = (panelWidth - (int) (image.getWidth() * 2.0)) / 2;
        int y = (panelHeight - (int) (image.getHeight() * 2.0)) / 2;

        // Draw the scaled image
        g2d.drawImage(image, x, y, (int) (image.getWidth() * 2.0), (int) (image.getHeight() * 2.0), null);
        //g.drawImage(image, x, y, this);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(512, 448);
    }
}

public class App
{
    public static void main(String[] cliArgs) throws IOException, InterruptedException {

        var fdWrite = new HostFunction(
                (Instance instance, Value... args) -> {
                    System.out.println("fdWrite: " + Arrays.toString(args));
                    return null;
                },
                "a",
                "a",
                List.of(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
                List.of(ValueType.I32));

        var resizeHeap = new HostFunction(
                (Instance instance, Value... args) -> {
                    System.out.println("resizeHeap: " + Arrays.toString(args));
                    return null;
                },
                "a",
                "b",
                List.of(ValueType.I32),
                List.of(ValueType.I32));

        var dateNow = new HostFunction(
                (Instance instance, Value... args) -> {
                    System.out.println("dateNow: " + Arrays.toString(args));
                    return null;
                },
                "a",
                "c",
                List.of(),
                List.of(ValueType.F64));

        var memcpy = new HostFunction(
                (Instance instance, Value... args) -> {
                    System.out.println("Memcpy: " + Arrays.toString(args));
                    var dest = args[0].asInt();
                    var src = args[1].asInt();
                    var size = args[2].asInt();
                    instance.memory().copy(dest, src, size);
                    return null;
                },
                "a",
                "d",
                List.of(ValueType.I32, ValueType.I32, ValueType.I32),
                List.of());

        var imports = new HostImports(new HostFunction[]{
                fdWrite, resizeHeap, dateNow, memcpy
        });

        var builder = Module
                .builder("snes9x_2005-raw.wasm")
                .withLogger(new SystemLogger())
                .withHostImports(imports);

        Module module = builder.build();

        //Module finalModule = module;
        //module = builder.withMachineFactory(instance -> new AotMachine(finalModule.wasmModule(), instance)).build();

        Instance instance = module.instantiate();

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
        var romLen = Value.i32(romBytes.length);
        var romPtr = malloc.apply(romLen)[0];
        instance.memory().write(romPtr.asInt(), romBytes);
        var sampleRate = Value.i32(36000);
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

        Value framePtr = null;

        while (true) {
            setJoypadInput.apply(Value.i32(0));
            long startTime = System.currentTimeMillis();
            mainLoop.apply();
            System.out.println("Main Loop: " + (System.currentTimeMillis() - startTime) + "ms");
            startTime = System.currentTimeMillis();
            framePtr = getScreenBuffer.apply()[0];
            var frame = instance.memory().readBytes(framePtr.asInt(), 512 * 448 * 2);
            renderer.updateFrame(frame);
            System.out.println("Render Frame: " + (System.currentTimeMillis() - startTime) + "ms");
            System.out.println("======================");
            //System.out.println(frame[(int) Math.round(Math.random() * 1000)]);
        }
    }
}
