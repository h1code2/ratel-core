package com.virjar.ratel.builder.helper.bro;

import com.google.common.io.Files;
import com.virjar.ratel.allcommon.NewConstants;
import com.virjar.ratel.builder.helper.apk2jar.APK2Jar;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipFile;
import org.apache.tools.zip.ZipOutputStream;
import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class OptimizeBuilderResource {
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(new Option("i", "input", true, "input apk file"));
        options.addOption(new Option("o", "output", true, "output jar file"));
        options.addOption(new Option("h", "help", false, "show help message"));

        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args, false);
        if (cmd.hasOption('h')) {
            HelpFormatter hf = new HelpFormatter();
            hf.setWidth(110);
            hf.printHelp("OPTIMIZE_BUILDER_RESOURCE", options);
            return;
        }

        File builderJarInputFile = new File(cmd.getOptionValue("input"));
        String output = cmd.getOptionValue("output");
        Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> optimizeData = null;
        ByteArrayOutputStream byteArrayOutputStream;
        try (ZipFile zipFile = new ZipFile(builderJarInputFile)) {
            optimizeData = handleJarInput(zipFile);

            if (output.endsWith("/")) {
                writeDataToDir(output, optimizeData);
                return;
            }
            if (!output.endsWith(".jar")) {
                throw new IllegalArgumentException("the output file must be jar file");
            }
            byteArrayOutputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
                writeDataToNewJar(zipOutputStream, zipFile, optimizeData);
            }
        }
        FileUtils.writeByteArrayToFile(new File(output), byteArrayOutputStream.toByteArray());
    }

    private static void writeDataToNewJar(ZipOutputStream zos, ZipFile zipFile, Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> optimizeData) throws IOException {
        HashMap<String, byte[]> data = new HashMap<>();
        for (NewConstants.BUILDER_RESOURCE_LAYOUT layout : optimizeData.keySet()) {
            data.put(layout.getNAME(), optimizeData.get(layout));
        }
        Enumeration<ZipEntry> entries = zipFile.getEntries();
        while (entries.hasMoreElements()) {
            ZipEntry zipEntry = entries.nextElement();
            if (NewConstants.BUILDER_RESOURCE_LAYOUT.BUILDER_HELPER_NAME2.getNAME().equals(zipEntry.getName())) {
                // 对于jar包本身，在构建工具完成优化之后，则不在需要helper了，此时需要把helper干掉
                // 但是在开发环境下，由于需要支持AndroidStudio的单步调试，这个时候需要把helper包含在其中，使用helper在调试的时候进行binder resource handling
                continue;
            }
            zos.putNextEntry(new ZipEntry(zipEntry));


            byte[] bytes = data.remove(zipEntry.getName());
            if (bytes != null) {
                IOUtils.write(bytes, zos);
            } else {
                IOUtils.copy(zipFile.getInputStream(zipEntry), zos);
            }
        }
        for (String key : data.keySet()) {
            zos.putNextEntry(new ZipEntry(key));
            IOUtils.write(data.get(key), zos);
        }
    }

    private static void writeDataToDir(String output, Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> optimizeData) throws IOException {
        //释放到文件夹下，这个时候应该是调试模式下，
        File dir = new File(output);
        for (NewConstants.BUILDER_RESOURCE_LAYOUT resource : optimizeData.keySet()) {
            final File file = new File(dir, resource.getNAME());
            FileUtils.forceMkdirParent(file);
            FileUtils.writeByteArrayToFile(file, optimizeData.get(resource));
        }
    }

    private static Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> handleJarInput(ZipFile zipFile) throws Exception {
        Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> optimizeData = new HashMap<>();

        // runtime 核心文件
        handleResource(zipFile,
                NewConstants.BUILDER_RESOURCE_LAYOUT.RUNTIME_APK_FILE,
                NewConstants.BUILDER_RESOURCE_LAYOUT.RUNTIME_JAR_FILE,
                new Apk2jarOptimizer("apk_to_jar.keep.runtime"), optimizeData);

        // xposed兼容层
        handleResource(zipFile,
                NewConstants.BUILDER_RESOURCE_LAYOUT.XPOSED_BRIDGE_APK_FILE,
                NewConstants.BUILDER_RESOURCE_LAYOUT.XPOSED_BRIDGE_JAR_FILE,
                new Apk2jarOptimizer("apk_to_jar.keep.xposedBridge"), optimizeData);


        // 注入使用的模版文件
        handleResource(zipFile,
                NewConstants.BUILDER_RESOURCE_LAYOUT.TEMPLATE_APK_FILE,
                NewConstants.BUILDER_RESOURCE_LAYOUT.TEMPLATE_DEX_FILE,
                new TemplateApkOptimizer(), optimizeData);

        // 模版文件解压为smali
        unpackTemplateSmali(optimizeData);
        return optimizeData;

    }

    private static void unpackTemplateSmali(Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> optimizeData) throws IOException {
        final byte[] bytes = optimizeData.get(NewConstants.BUILDER_RESOURCE_LAYOUT.TEMPLATE_DEX_FILE);
        DexBackedDexFile dexFile = new DexBackedDexFile(Opcodes.getDefault(), bytes);
        final BaksmaliOptions options = new BaksmaliOptions();

        options.localsDirective = true;
        options.sequentialLabels = true;
        options.accessorComments = false;


        File tempDir = Files.createTempDir();
        // 小文件，其实一个线程就够了
        Baksmali.disassembleDexFile(dexFile, tempDir, Runtime.getRuntime().availableProcessors(), options);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            addToZip(zipOutputStream, tempDir, tempDir);
        }
        FileUtils.deleteDirectory(tempDir);

        optimizeData.put(NewConstants.BUILDER_RESOURCE_LAYOUT.TEMPLATE_SMALI_ZIP_FILE, byteArrayOutputStream.toByteArray());
    }

    private static void addToZip(ZipOutputStream zos, File root, File nowFile) throws IOException {
        String name = nowFile.getAbsolutePath().substring(root.getAbsolutePath().length());
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (nowFile.isFile()) {
            zos.putNextEntry(new ZipEntry(name));
            IOUtils.copy(new FileInputStream(nowFile), zos);
            return;
        }
        if (!nowFile.isDirectory()) {
            return;
        }
        final File[] files = nowFile.listFiles();
        if (files == null) {
            return;
        }
        if (!name.isEmpty()) {
            zos.putNextEntry(new ZipEntry(name + "/"));
        }
        for (File file : files) {
            addToZip(zos, root, file);
        }
    }

    private static class TemplateApkOptimizer implements Transformer {
        @Override
        public byte[] transform(byte[] input) throws Exception {
            // 对于模版apk，首先需要进行一次class优化，删除无效的class
            byte[] optimizedJarData = new Apk2jarOptimizer("apk_to_jar.keep.injectTemplate").transform(input);
            File outJar = File.createTempFile("ratel-template-tmp", ".jar");
            FileUtils.writeByteArrayToFile(outJar, optimizedJarData);
            // 把所有的dex合并到同一个dex文件中，由于这是模版文件，这里完全不用考虑dex膨胀溢出问题
            DexPool dexPool = new DexPool(Opcodes.getDefault());
            try (ZipFile zipFile = new ZipFile(outJar)) {
                Pattern classesPattern = Pattern.compile("classes(\\d*)\\.dex");
                Enumeration<ZipEntry> entries = zipFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipEntry zipEntry = entries.nextElement();
                    if (!classesPattern.matcher(zipEntry.getName()).matches()) {
                        continue;
                    }
                    DexBackedDexFile dexBackedDexFile = new DexBackedDexFile(Opcodes.getDefault(), IOUtils.toByteArray(zipFile.getInputStream(zipEntry)));
                    for (ClassDef classDef : dexBackedDexFile.getClasses()) {
                        dexPool.internClass(classDef);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            MemoryDataStore memoryDataStore = new MemoryDataStore();
            dexPool.writeTo(memoryDataStore);
            memoryDataStore.close();
            return memoryDataStore.getData();
        }
    }

    private static class Apk2jarOptimizer implements Transformer {
        private final String scene;

        public Apk2jarOptimizer(String scene) {
            this.scene = scene;
        }

        @Override
        public byte[] transform(byte[] input) throws Exception {
            File apkFile = File.createTempFile("ratel-apk2jar-tmp", ".apk");
            File outJar = File.createTempFile("ratel-apk2jar-tmp", ".jar");
            FileUtils.writeByteArrayToFile(apkFile, input);
            APK2Jar.main(new String[]{
                    "--input",
                    apkFile.getAbsolutePath(),
                    "--output",
                    outJar.getAbsolutePath(),
                    "--keepScene",
                    scene
            });
            ZipFile zipFile = null;
            try {
                zipFile = new ZipFile(outJar);
            } finally {
                IOUtils.closeQuietly(zipFile);
            }
            return FileUtils.readFileToByteArray(outJar);
        }
    }


    private static void handleResource(ZipFile zipFile, NewConstants.BUILDER_RESOURCE_LAYOUT rowKey, NewConstants.BUILDER_RESOURCE_LAYOUT optimizedKey, Transformer transformer, Map<NewConstants.BUILDER_RESOURCE_LAYOUT, byte[]> optimizeData) throws Exception {
        ZipEntry rowResource = zipFile.getEntry(rowKey.getNAME());
        if (rowResource != null) {
            byte[] bytes = IOUtils.toByteArray(zipFile.getInputStream(rowResource));
            optimizeData.put(optimizedKey, transformer.transform(bytes));
            return;
        }
        ZipEntry optimizeResource = zipFile.getEntry(optimizedKey.getNAME());
        if (optimizeResource != null) {
            optimizeData.put(optimizedKey, IOUtils.toByteArray(zipFile.getInputStream(optimizeResource)));
            return;
        }
        throw new IOException("can not find resource from jar file with key: (" + rowKey + "," + optimizedKey + ")");

    }

    private interface Transformer {
        byte[] transform(byte[] input) throws Exception;
    }
}
