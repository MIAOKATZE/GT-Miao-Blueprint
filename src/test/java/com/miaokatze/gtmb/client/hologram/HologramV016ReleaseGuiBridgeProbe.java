package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Standalone JVM ABI witness. Target class bytes are never remapped or modified. */
public final class HologramV016ReleaseGuiBridgeProbe implements Opcodes {

    private static final String BASE = "com/miaokatze/gtmb/client/hologram/";
    private static final String HOST = BASE + "HologramUiHost";
    private static final String SCREEN = BASE + "HologramScreen";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String GUI_SCREEN = GUI + "Screen";
    private static final String FONT = "net/minecraft/client/gui/FontRenderer";
    private static final String MC = "net/minecraft/client/Minecraft";
    // Runtime tasks supply ASM9; compilation also supports Forge's older ASM declarations.
    private static final int MIGRATION_ASM = 9 << 16;
    public static final Map<String, Integer> CALLS = new LinkedHashMap<String, Integer>();

    public static void hit(String method) {
        Integer old = CALLS.get(method);
        CALLS.put(method, old == null ? 1 : old + 1);
    }

    private static byte[] entry(ZipFile zip, String name) throws Exception {
        if (zip.getEntry(name + ".class") == null) throw new IllegalStateException("Missing " + name);
        java.io.InputStream in = zip.getInputStream(zip.getEntry(name + ".class"));
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void constructor(ClassWriter cw, String parent) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] simple(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Only these methods have bodies in the fixture; names must exist in the actual SRG runtime.
    private static final List<String> DRAW_METHODS = Arrays
        .asList("func_146276_q_", "func_73731_b", "func_73732_a", "drawHoveringText");

    private static byte[] guiFixture(byte[] source, final byte[] forgePatched, final List<String> contract) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        final String[] owner = new String[1];
        new ClassReader(source).accept(new ClassVisitor(ASM5) {

            @Override
            public void visit(int v, int access, String name, String sig, String parent, String[] interfaces) {
                owner[0] = name;
                cw.visit(V1_8, ACC_PUBLIC, name, null, parent, null);
                constructor(cw, parent);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (Arrays.asList("field_146297_k", "field_146289_q", "field_146294_l", "field_146295_m")
                    .contains(name)) {
                    contract.add(owner[0] + "." + name + desc);
                    return cw.visitField(ACC_PUBLIC, name, desc, null, null);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!DRAW_METHODS.contains(name)) return null;
                if (Type.getReturnType(desc)
                    .getSort() != Type.VOID)
                    throw new IllegalStateException("Unexpected drawing return " + name + desc);
                contract.add(owner[0] + "." + name + desc);
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, exceptions);
                mv.visitCode();
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "com/miaokatze/gtmb/client/hologram/HologramV016ReleaseGuiBridgeProbe",
                    "hit",
                    "(Ljava/lang/String;)V",
                    false);
                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        // The vanilla SRG merge precedes Forge patches. Its stable, unmapped tooltip overload
        // is witnessed separately in the actual Forge-patched runtime class, never invented.
        if (forgePatched != null) new ClassReader(forgePatched).accept(new ClassVisitor(ASM5) {

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!name.equals("drawHoveringText") || contract.contains(owner[0] + "." + name + desc)) return null;
                contract.add(owner[0] + "." + name + desc);
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, exceptions);
                mv.visitCode();
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "com/miaokatze/gtmb/client/hologram/HologramV016ReleaseGuiBridgeProbe",
                    "hit",
                    "(Ljava/lang/String;)V",
                    false);
                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] minecraftFixture(byte[] source) {
        final boolean[] found = { false };
        new ClassReader(source).accept(new ClassVisitor(ASM5) {

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if (n.equals("func_71410_x") && d.equals("()L" + MC + ";")) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!found[0]) throw new IllegalStateException("Runtime is not SRG Minecraft");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, MC, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "func_71410_x", "()L" + MC + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, MC);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, MC, "<init>", "()V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class Isolated extends ClassLoader {

        final Map<String, byte[]> bytes = new LinkedHashMap<String, byte[]>();

        Isolated() {
            super(HologramV016ReleaseGuiBridgeProbe.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(HologramV016ReleaseGuiBridgeProbe.class.getName())) return super.loadClass(name, resolve);
            if (name.startsWith("com.miaokatze.gtmb.") || name.startsWith("net.minecraft.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        byte[] data = bytes.get(name.replace('.', '/'));
                        if (data == null) data = simple(name.replace('.', '/'));
                        c = defineClass(name, data, 0, data.length);
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }

        Class<?> define(String name, byte[] data) {
            return defineClass(name, data, 0, data.length);
        }
    }

    private static List<String[]> interfaceMethods(byte[] source) {
        final List<String[]> methods = new ArrayList<String[]>();
        new ClassReader(source).accept(new ClassVisitor(ASM5) {

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if ((a & ACC_ABSTRACT) != 0) methods.add(new String[] { n, d });
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Collections.sort(methods, new java.util.Comparator<String[]>() {

            public int compare(String[] a, String[] b) {
                return a[0].compareTo(b[0]);
            }
        });
        return methods;
    }

    private static byte[] caller(List<String[]> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, BASE + "V016DirectInterfaceCaller", null, "java/lang/Object", null);
        for (int i = 0; i < methods.size(); i++) {
            String[] method = methods.get(i);
            String desc = "(L" + HOST + ";" + method[1].substring(1);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call" + i, desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            int slot = 1;
            for (Type arg : Type.getArgumentTypes(method[1])) {
                mv.visitVarInsn(arg.getOpcode(ILOAD), slot);
                slot += arg.getSize();
            }
            mv.visitMethodInsn(INVOKEINTERFACE, HOST, method[0], method[1], true);
            mv.visitInsn(
                Type.getReturnType(method[1])
                    .getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\"";
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4) {
            migrationAbi(args);
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException(
            "normal.jar srg_merged_minecraft.jar forge_patched_minecraft.jar [normal-mui2-2.3.92.jar]");
        Isolated loader = new Isolated();
        List<String> contract = new ArrayList<String>();
        ZipFile target = new ZipFile(new File(args[0]));
        ZipFile runtime = new ZipFile(new File(args[1]));
        ZipFile forgeRuntime = new ZipFile(new File(args[2]));
        byte[] host;
        try {
            host = entry(target, HOST);
            loader.bytes.put(HOST, host);
            loader.bytes.put(SCREEN, entry(target, SCREEN));
            loader.bytes.put(GUI, guiFixture(entry(runtime, GUI), null, contract));
            loader.bytes
                .put(GUI_SCREEN, guiFixture(entry(runtime, GUI_SCREEN), entry(forgeRuntime, GUI_SCREEN), contract));
            loader.bytes.put(MC, minecraftFixture(entry(runtime, MC)));
            loader.bytes.put(FONT, simple(FONT));
        } finally {
            target.close();
            runtime.close();
            forgeRuntime.close();
        }
        for (String method : DRAW_METHODS) {
            boolean exists = false;
            for (String item : contract) if (item.contains("." + method + "(")) exists = true;
            if (!exists) throw new IllegalStateException("Runtime lacks required SRG/Forge method: " + method);
        }
        List<String[]> methods = interfaceMethods(host);
        if (methods.size() != 9) throw new IllegalStateException("Review changed host surface: " + methods.size());
        Class<?> screenType = loader.loadClass(SCREEN.replace('/', '.'));
        Class<?> hostType = loader.loadClass(HOST.replace('/', '.'));
        Object screen = allocate(screenType);
        Object mc = loader.loadClass(MC.replace('/', '.'))
            .getConstructor()
            .newInstance();
        Object font = loader.loadClass(FONT.replace('/', '.'))
            .getConstructor()
            .newInstance();
        Class<?> base = loader.loadClass(GUI_SCREEN.replace('/', '.'));
        base.getField("field_146297_k")
            .set(screen, mc);
        base.getField("field_146289_q")
            .set(screen, font);
        base.getField("field_146294_l")
            .setInt(screen, 854);
        base.getField("field_146295_m")
            .setInt(screen, 480);
        Class<?> calls = loader.define((BASE + "V016DirectInterfaceCaller").replace('/', '.'), caller(methods));
        List<String> results = new ArrayList<String>();
        int passed = 0;
        int ame = 0;
        int other = 0;
        for (int i = 0; i < methods.size(); i++) {
            String name = methods.get(i)[0];
            Type[] argTypes = Type.getArgumentTypes(methods.get(i)[1]);
            Class<?>[] params = new Class<?>[argTypes.length + 1];
            Object[] values = new Object[argTypes.length + 1];
            params[0] = hostType;
            values[0] = screen;
            for (int j = 0; j < argTypes.length; j++) {
                Type t = argTypes[j];
                if (t.getSort() == Type.INT) {
                    params[j + 1] = int.class;
                    values[j + 1] = 1;
                } else {
                    params[j + 1] = loader.loadClass(t.getClassName());
                    values[j + 1] = t.getClassName()
                        .equals("java.lang.String") ? "v016"
                            : t.getClassName()
                                .equals("java.util.List") ? Arrays.asList("v016") : font;
                }
            }
            try {
                Object value = calls.getMethod("call" + i, params)
                    .invoke(null, values);
                if (name.equals("parent") && value != screen) throw new AssertionError("parent identity");
                if (name.equals("minecraft") && value != mc) throw new AssertionError("minecraft identity");
                if (name.equals("font") && value != font) throw new AssertionError("font identity");
                if (name.equals("screenWidth") && !Integer.valueOf(854)
                    .equals(value)) throw new AssertionError("width");
                if (name.equals("screenHeight") && !Integer.valueOf(480)
                    .equals(value)) throw new AssertionError("height");
                passed++;
                results.add(name + ":PASS");
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof AbstractMethodError) ame++;
                else other++;
                results.add(
                    name + ":"
                        + cause.getClass()
                            .getName()
                        + ":"
                        + cause.getMessage());
            }
        }
        boolean forwarded = true;
        for (String method : DRAW_METHODS) if (!Integer.valueOf(1)
            .equals(CALLS.get(method))) forwarded = false;
        String status = passed == 9 && forwarded ? "PASS" : "FAIL";
        StringBuilder json = new StringBuilder(
            "{\"status\":" + quote(status)
                + ",\"scope\":\"unchanged normal JAR, direct invokeinterface, SRG ABI fixture; no FML/GL/native GUI claim\""
                + ",\"passed\":"
                + passed
                + ",\"abstractMethodErrors\":"
                + ame
                + ",\"otherErrors\":"
                + other
                + ",\"forwardedAllFour\":"
                + forwarded
                + ",\"targetBytesModified\":false,\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) json.append(',');
            json.append(quote(results.get(i)));
        }
        json.append("],\"srgContract\":[");
        for (int i = 0; i < contract.size(); i++) {
            if (i > 0) json.append(',');
            json.append(quote(contract.get(i)));
        }
        json.append("]}");
        System.out.println(json);
        if (!status.equals("PASS")) System.exit(1);
    }

    /** Reads actual release/library bytes. This route deliberately defines no fixture classes. */
    private static void migrationAbi(String[] args) throws Exception {
        final Map<String, ClassReader> classes = new LinkedHashMap<String, ClassReader>();
        final List<String> screens = new ArrayList<String>();
        final Set<String> libraries = new HashSet<String>();
        for (int input : new int[] { 1, 2, 3, 0 }) {
            try (ZipFile zip = new ZipFile(args[input])) {
                Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement()
                        .getName();
                    if (!name.endsWith(".class")) continue;
                    String type = name.substring(0, name.length() - 6);
                    ClassReader actual = new ClassReader(entry(zip, type));
                    if (input == 2) {
                        classes.put("forgepatch/" + type, actual);
                        if (!classes.containsKey(type)) classes.put(type, actual);
                    } else classes.put(type, actual);
                    if (input == 3 && type.startsWith("com/cleanroommc/modularui/")) libraries.add(type);
                    if (input == 0 && type.startsWith(BASE)
                        && (type.endsWith("Screen") || type.startsWith(BASE + "HologramMui")
                            || type.startsWith(BASE + "HologramUi")))
                        screens.add(type);
                }
                if (input == 3) {
                    if (zip.getEntry("mcmod.info") == null) throw new AssertionError("MUI2 mod metadata missing");
                    java.io.InputStream in = zip.getInputStream(zip.getEntry("mcmod.info"));
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    try {
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
                    } finally {
                        in.close();
                    }
                    if (!new String(out.toByteArray(), "UTF-8").contains("2.3.92"))
                        throw new AssertionError("Actual MUI2 2.3.92 normal library required");
                }
            }
        }
        if (!libraries.contains("com/cleanroommc/modularui/screen/GuiScreenWrapper"))
            throw new AssertionError("Actual MUI2 wrapper absent");
        if (!inherits(classes, SCREEN, "com/cleanroommc/modularui/screen/GuiScreenWrapper", new HashSet<String>()))
            throw new AssertionError("Workstation does not use the actual MUI2 wrapper");
        List<String[]> ports;
        try (ZipFile target = new ZipFile(args[0])) {
            ports = interfaceMethods(entry(target, HOST));
        }
        if (ports.size() != 9) throw new AssertionError("Review changed Host contract: " + ports.size());
        for (String[] port : ports) {
            Integer access = declaredMember(required(classes, SCREEN), port[0], port[1], false);
            if (access == null || (access & (ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT)) != ACC_PUBLIC)
                throw new AssertionError("Normal Screen lacks explicit concrete Host port " + port[0] + port[1]);
        }
        final Set<String> checked = new HashSet<String>();
        final List<String> failures = new ArrayList<String>();
        for (final String screen : screens) {
            required(classes, screen).accept(new ClassVisitor(MIGRATION_ASM) {

                @Override
                public MethodVisitor visitMethod(int a, final String n, String d, String s, String[] e) {
                    return new MethodVisitor(MIGRATION_ASM) {

                        private void check(String owner, String name, String desc, boolean field) {
                            if (!owner.startsWith(BASE) && !owner.startsWith("net/minecraft/")
                                && !owner.startsWith("com/cleanroommc/modularui/")) return;
                            String key = owner + "." + name + desc;
                            checked.add(key);
                            if (!resolves(classes, owner, name, desc, field, new HashSet<String>()))
                                failures.add(screen + "." + n + " -> unresolved " + key);
                        }

                        @Override
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            check(owner, name, desc, false);
                        }

                        @Override
                        public void visitFieldInsn(int op, String owner, String name, String desc) {
                            check(owner, name, desc, true);
                        }

                        @Override
                        public void visitTypeInsn(int op, String type) {
                            if (type.equals("net/minecraft/client/gui/GuiButton")
                                || type.equals("net/minecraft/client/gui/GuiTextField"))
                                failures.add(screen + "." + n + " uses vanilla control " + type);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        if (screens.size() < 6 || checked.size() < 10) throw new AssertionError("Migration surface was not inspected");
        Collections.sort(screens);
        System.out.println(
            "{\"status\":" + quote(failures.isEmpty() ? "PASS" : "FAIL")
                + ",\"scope\":\"actual unchanged normal GTMB and MUI2 2.3.92 bytes; static inheritance/member ABI witness; no class loading, FML, GL or native GUI claim\""
                + ",\"passed\":"
                + (failures.isEmpty() ? 9 : 0)
                + ",\"hostPorts\":9,\"mui2Version\":\"2.3.92\""
                + ",\"referencesChecked\":"
                + checked.size()
                + ",\"screens\":"
                + quote(screens.toString())
                + ",\"targetBytesModified\":false,\"fixtureClassesDefined\":false,\"failures\":"
                + quote(failures.toString())
                + "}");
        if (!failures.isEmpty()) throw new AssertionError(failures.toString());
    }

    private static ClassReader required(Map<String, ClassReader> classes, String type) {
        ClassReader reader = classes.get(type);
        if (reader == null) throw new AssertionError("Actual input class missing: " + type);
        return reader;
    }

    private static Integer declaredMember(ClassReader reader, final String name, final String desc, boolean field) {
        final Integer[] found = { null };
        reader.accept(new ClassVisitor(MIGRATION_ASM) {

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if (!field && n.equals(name) && d.equals(desc)) found[0] = a;
                return null;
            }

            @Override
            public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                if (field && n.equals(name) && d.equals(desc)) found[0] = a;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean resolves(Map<String, ClassReader> classes, String owner, String name, String desc,
        boolean field, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        ClassReader reader = classes.get(owner);
        if (reader == null && owner.startsWith("java/")) {
            try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(owner + ".class")) {
                if (in != null) {
                    reader = new ClassReader(in);
                    classes.put(owner, reader);
                }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cannot inspect actual JDK ABI " + owner, failure);
            }
        }
        if (reader == null) return false;
        if (declaredMember(reader, name, desc, field) != null) return true;
        ClassReader patched = classes.get("forgepatch/" + owner);
        if (patched != null && declaredMember(patched, name, desc, field) != null) return true;
        if (name.equals("<init>")) return false;
        if (resolves(classes, reader.getSuperName(), name, desc, field, seen)) return true;
        for (String itf : reader.getInterfaces()) if (resolves(classes, itf, name, desc, field, seen)) return true;
        return false;
    }

    private static boolean inherits(Map<String, ClassReader> classes, String owner, String parent, Set<String> seen) {
        if (parent.equals(owner)) return true;
        if (owner == null || !seen.add(owner)) return false;
        ClassReader reader = classes.get(owner);
        if (reader == null) return false;
        if (inherits(classes, reader.getSuperName(), parent, seen)) return true;
        for (String itf : reader.getInterfaces()) if (inherits(classes, itf, parent, seen)) return true;
        return false;
    }
}
