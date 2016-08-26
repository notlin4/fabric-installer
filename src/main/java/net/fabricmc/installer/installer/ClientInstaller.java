package net.fabricmc.installer.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cuchaz.enigma.Deobfuscator;
import cuchaz.enigma.TranslatingTypeLoader;
import cuchaz.enigma.mapping.MappingsEnigmaReader;
import cuchaz.enigma.mapping.TranslationDirection;
import cuchaz.enigma.throwables.MappingParseException;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtField;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.InnerClassesAttribute;
import net.fabricmc.installer.utill.IInstallerProgress;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

public class ClientInstaller {

	public static void install(File mcDir, String version, IInstallerProgress progress) throws IOException, MappingParseException {
		progress.updateProgress("Installing: " + version, 0);
		String[] split = version.split("-");
		if (isValidInstallLocation(mcDir, split[0]).isPresent()) {
			throw new RuntimeException(isValidInstallLocation(mcDir, split[0]).get());
		}
		File fabricData = new File(mcDir, "fabricData");
		File fabricJar = new File(fabricData, version + ".jar");
		if (!fabricJar.exists()) {
			progress.updateProgress("Downloading fabric", 10);
			FileUtils.copyURLToFile(new URL("http://maven.fabricmc.net/net/fabricmc/fabric-base/" + version + "/fabric-base-" + version + ".jar"), fabricJar);
		}

		JarFile jarFile = new JarFile(fabricJar);
		Attributes attributes = jarFile.getManifest().getMainAttributes();

		String id = "fabric-" + attributes.getValue("FabricVersion");
		System.out.println("Installing " + id);
		File versionsFolder = new File(mcDir, "versions");
		File fabricVersionFolder = new File(versionsFolder, id);
		File mcVersionFolder = new File(versionsFolder, split[0]);
		File fabricJsonFile = new File(fabricVersionFolder, id + ".json");
		File fabricJarFile = new File(fabricVersionFolder, id + ".jar");
		File mcJsonFile = new File(mcVersionFolder, split[0] + ".json");
		File mcJarFile = new File(mcVersionFolder, split[0] + ".jar");
		if (fabricVersionFolder.exists()) {
			progress.updateProgress("Removing old fabric version", 10);
			FileUtils.deleteDirectory(fabricVersionFolder);
		}
		fabricVersionFolder.mkdirs();

		FileUtils.copyFile(mcJsonFile, fabricJsonFile);
		progress.updateProgress("Creating version Json File", 20);

		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		JsonElement jsonElement = gson.fromJson(new FileReader(fabricJsonFile), JsonElement.class);
		JsonObject jsonObject = jsonElement.getAsJsonObject();
		jsonObject.remove("id");
		jsonObject.addProperty("id", id);

		jsonObject.remove("mainClass");
		jsonObject.addProperty("mainClass", "net.minecraft.launchwrapper.Launch");

		jsonObject.remove("downloads");

		String args = jsonObject.get("minecraftArguments").getAsString();
		jsonObject.remove("minecraftArguments");
		jsonObject.addProperty("minecraftArguments", args + " --tweakClass net.fabricmc.base.launch.FabricClientTweaker");

		JsonArray librarys = jsonObject.getAsJsonArray("libraries");

		addDep("net.fabricmc:fabric-base:" + attributes.getValue("FabricVersion"), "http://maven.fabricmc.net/", librarys);

		File depJson = new File(mcVersionFolder, "dependencies.json");
		ZipUtil.unpack(fabricJar, mcVersionFolder, name -> {
			if (name.startsWith("dependencies.json")) {
				return name;
			} else {
				return null;
			}
		});
		JsonElement depElement = gson.fromJson(new FileReader(depJson), JsonElement.class);
		JsonObject depObject = depElement.getAsJsonObject();
		librarys.addAll(depObject.getAsJsonArray("libraries"));

		FileUtils.write(fabricJsonFile, gson.toJson(jsonElement), "UTF-8");

		progress.updateProgress("Creating temporary directory for files", 40);

		File tempWorkDir = new File(fabricVersionFolder, "temp");
		if (tempWorkDir.exists()) {
			FileUtils.deleteDirectory(tempWorkDir);
		}
		progress.updateProgress("Extracting Mappings", 50);
		ZipUtil.unpack(fabricJar, tempWorkDir, name -> {
			if (name.startsWith("pomf-" + split[0])) {
				return name;
			} else {
				return null;
			}
		});

		File mappingsDir = new File(tempWorkDir, "pomf-" + split[0] + File.separator + "mappings");
		File tempAssests = new File(tempWorkDir, "assets");
		progress.updateProgress("Loading jar file into deobfuscator", 60);
		Deobfuscator deobfuscator = new Deobfuscator(new JarFile(mcJarFile));
		progress.updateProgress("Reading mappings", 65);
		deobfuscator.setMappings(new MappingsEnigmaReader().read(mappingsDir));
		progress.updateProgress("Writing out new jar file", 70);
		writeJar(fabricJarFile, new ProgressListener(), deobfuscator);
		progress.updateProgress("Cleaning minecraft jar file", 80);
		ZipUtil.unpack(mcJarFile, tempAssests, name -> {
			if (name.startsWith("assets") || name.startsWith("log4j2.xml") || name.startsWith("pack.png")) {
				return name;
			} else {
				return null;
			}
		});
		ZipUtil.unpack(fabricJarFile, tempAssests);
		ZipUtil.pack(tempAssests, fabricJarFile);

		progress.updateProgress("Removing temp directory", 90);
		FileUtils.deleteDirectory(tempWorkDir);
		progress.updateProgress("Done!", 100);
	}

	public static void addDep(String dep, String maven, JsonArray jsonArray) {
		JsonObject object = new JsonObject();
		object.addProperty("name", dep);
		if (!maven.isEmpty()) {
			object.addProperty("url", maven);
		}
		jsonArray.add(object);
	}

	public static Optional<String> isValidInstallLocation(File mcDir, String mcVer) {
		if (!mcDir.isDirectory()) {
			return Optional.of(mcDir.getName() + " is not a directory");
		}
		File versionsFolder = new File(mcDir, "versions");
		if (!versionsFolder.exists() || !versionsFolder.isDirectory()) {
			return Optional.of("Please launch vanilla Minecraft version " + mcVer);
		}
		File versionFolder = new File(versionsFolder, mcVer);
		if (!versionsFolder.exists() || !versionsFolder.isDirectory()) {
			return Optional.of("Please launch vanilla Minecraft version " + mcVer);
		}

		File mcJsonFile = new File(versionFolder, mcVer + ".json");
		File mcJarFile = new File(versionFolder, mcVer + ".jar");
		if (!mcJsonFile.exists() || !mcJarFile.exists()) {
			return Optional.of("Please launch vanilla Minecraft version " + mcVer);
		}

		//All is ok
		return Optional.empty();
	}

	public static class ProgressListener implements Deobfuscator.ProgressListener {
		@Override
		public void init(int i, String s) {

		}

		@Override
		public void onProgress(int i, String s) {

		}
	}

	public static void writeJar(File out, Deobfuscator.ProgressListener progress, Deobfuscator deobfuscator) {
		TranslatingTypeLoader loader = new TranslatingTypeLoader(deobfuscator.getJar(), deobfuscator.getJarIndex(), deobfuscator.getTranslator(TranslationDirection.Obfuscating), deobfuscator.getTranslator(TranslationDirection.Deobfuscating));
		deobfuscator.transformJar(out, progress, new CustomClassTransformer(loader));
	}

	private static class CustomClassTransformer implements Deobfuscator.ClassTransformer {

		TranslatingTypeLoader loader;

		public CustomClassTransformer(TranslatingTypeLoader loader) {
			this.loader = loader;
		}

		@Override
		public CtClass transform(CtClass ctClass) throws Exception {
			return publify(loader.transformClass(ctClass));
		}
	}

	//Taken from enigma, anc changed a little
	public static CtClass publify(CtClass c) {

		for (CtField field : c.getDeclaredFields()) {
			field.setModifiers(publify(field.getModifiers()));
		}
		for (CtBehavior behavior : c.getDeclaredBehaviors()) {
			behavior.setModifiers(publify(behavior.getModifiers()));
		}
		InnerClassesAttribute attr = (InnerClassesAttribute) c.getClassFile().getAttribute(InnerClassesAttribute.tag);
		if (attr != null) {
			for (int i = 0; i < attr.tableLength(); i++) {
				attr.setAccessFlags(i, publify(attr.accessFlags(i)));
			}
		}

		return c;
	}

	private static int publify(int flags) {
		if (!AccessFlag.isPublic(flags)) {
			flags = AccessFlag.setPublic(flags);
		}
		return flags;
	}

}