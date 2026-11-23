package ch.njol.skript.lang.il;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary serialization of a script's IL.
 * <p>
 * The format is versioned: a fixed header (magic, format version, Skript version,
 * parse environment fingerprint, source hash) followed by the recorded entries.
 */
final class IlFile {

	private static final int MAGIC = 0x534B494C; // "SKIL"
	static final int FORMAT_VERSION = 1;

	private IlFile() { }

	static void save(File file, String sourceHash, String environmentFingerprint,
					 Map<String, IlManager.Entry> entries) throws IOException {
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs())
			throw new IOException("Could not create cache directory " + parent);
		File temp = new File(file.getPath() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
				new GZIPOutputStream(Files.newOutputStream(temp.toPath()))))) {
			out.writeInt(MAGIC);
			out.writeInt(FORMAT_VERSION);
			writeString(out, VERSION);
			writeString(out, environmentFingerprint);
			writeString(out, sourceHash);
			out.writeInt(entries.size());
			for (Map.Entry<String, IlManager.Entry> mapEntry : entries.entrySet()) {
				String key = mapEntry.getKey();
				IlManager.Entry entry = mapEntry.getValue();
				writeString(out, key);
				out.writeByte(entry.state());
				out.writeInt(entry.flags());
				out.writeInt(entry.parseContext());
				writeString(out, entry.expr());
				if (entry.state() == IlManager.STATE_NODE) {
					assert entry.node() != null;
					writeNode(out, entry.node());
				}
			}
		}
		try {
			Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * @return the loaded entries, or {@code null} if the cache is missing, corrupt or does not match the given
	 * source hash and environment fingerprint.
	 */
	static @Nullable Map<String, IlManager.Entry> load(File file, String sourceHash,
													   String environmentFingerprint) {
		if (!file.isFile())
			return null;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(
				new GZIPInputStream(Files.newInputStream(file.toPath()))))) {
			if (in.readInt() != MAGIC)
				return null;
			if (in.readInt() != FORMAT_VERSION)
				return null;
			if (!VERSION.equals(readString(in)))
				return null;
			if (!environmentFingerprint.equals(readString(in)))
				return null;
			if (!sourceHash.equals(readString(in)))
				return null;
			int count = in.readInt();
			if (count < 0 || count > 10_000_000)
				return null;
			Map<String, IlManager.Entry> entries = new HashMap<>(Math.max(16, count * 2));
			for (int i = 0; i < count; i++) {
				String key = readString(in);
				byte state = in.readByte();
				int flags = in.readInt();
				int parseContext = in.readInt();
				String expr = readString(in);
				IlNode node = state == IlManager.STATE_NODE ? readNode(in) : null;
				entries.put(key, new IlManager.Entry(state, node, flags, parseContext, expr));
			}
			return entries;
		} catch (Exception e) {
			return null;
		}
	}

	/*
	 * Node serialization
	 */

	private static void writeNode(DataOutputStream out, IlNode node) throws IOException {
		switch (node) {
			case IlNode.Element element -> {
				out.writeByte(0);
				writeString(out, element.className());
				out.writeInt(element.patternIndex());
				writeString(out, element.expr());
				out.writeInt(element.mark());
				writeStrings(out, element.tags());
				out.writeInt(element.regexes().size());
				for (IlNode.Regex regex : element.regexes()) {
					writeStrings(out, regex.groups());
					writeInts(out, regex.starts());
					writeInts(out, regex.ends());
				}
				out.writeInt(element.args().size());
				for (IlNode.Arg arg : element.args()) {
					writeArg(out, arg);
				}
			}
			case IlNode.Variable variable -> {
				out.writeByte(1);
				writeString(out, variable.name());
				writeStrings(out, variable.typeNames());
			}
			case IlNode.VarString varString -> {
				out.writeByte(2);
				writeString(out, varString.original());
				writeString(out, varString.mode());
			}
			case IlNode.Unparsed unparsed -> {
				out.writeByte(3);
				writeString(out, unparsed.text());
			}
			case IlNode.Literal literal -> {
				out.writeByte(4);
				writeString(out, literal.text());
				writeString(out, literal.attemptedTypeName());
				out.writeInt(literal.parseContext());
			}
			case IlNode.SpecifiedLiteral specifiedLiteral -> {
				out.writeByte(5);
				writeString(out, specifiedLiteral.literalText());
				writeString(out, specifiedLiteral.classInfoName());
				out.writeInt(specifiedLiteral.parseContext());
			}
			case IlNode.FunctionCall functionCall -> {
				out.writeByte(6);
				writeString(out, functionCall.text());
				writeStrings(out, functionCall.typeNames());
				out.writeInt(functionCall.flags());
				out.writeInt(functionCall.parseContext());
			}
			case IlNode.ListExpr listExpr -> {
				out.writeByte(7);
				out.writeBoolean(listExpr.literalList());
				out.writeBoolean(listExpr.and());
				out.writeInt(listExpr.items().size());
				for (IlNode item : listExpr.items())
					writeNode(out, item);
			}
			case IlNode.Converted converted -> {
				out.writeByte(8);
				writeStrings(out, converted.typeNames());
				writeNode(out, converted.child());
			}
		}
	}

	private static IlNode readNode(DataInputStream in) throws IOException {
		byte type = in.readByte();
		return switch (type) {
			case 0 -> {
				String className = readString(in);
				int patternIndex = in.readInt();
				String expr = readString(in);
				int mark = in.readInt();
				List<String> tags = readStrings(in);
				int regexCount = in.readInt();
				List<IlNode.Regex> regexes = new ArrayList<>(regexCount);
				for (int i = 0; i < regexCount; i++) {
					regexes.add(new IlNode.Regex(readStrings(in), readInts(in), readInts(in)));
				}
				int argCount = in.readInt();
				List<IlNode.Arg> args = new ArrayList<>(argCount);
				for (int i = 0; i < argCount; i++)
					args.add(readArg(in));
				yield new IlNode.Element(className, patternIndex, expr, mark, tags, regexes, args);
			}
			case 1 -> new IlNode.Variable(readString(in), readStrings(in));
			case 2 -> new IlNode.VarString(readString(in), readString(in));
			case 3 -> new IlNode.Unparsed(readString(in));
			case 4 -> new IlNode.Literal(readString(in), readString(in), in.readInt());
			case 5 -> new IlNode.SpecifiedLiteral(readString(in), readString(in), in.readInt());
			case 6 -> new IlNode.FunctionCall(readString(in), readStrings(in), in.readInt(), in.readInt());
			case 7 -> {
				boolean literalList = in.readBoolean();
				boolean and = in.readBoolean();
				int size = in.readInt();
				List<IlNode> items = new ArrayList<>(size);
				for (int i = 0; i < size; i++)
					items.add(readNode(in));
				yield new IlNode.ListExpr(items, literalList, and);
			}
			case 8 -> {
				List<String> typeNames = readStrings(in);
				yield new IlNode.Converted(readNode(in), typeNames);
			}
			default -> throw new IOException("Unknown IL node type " + type);
		};
	}

	private static void writeArg(DataOutputStream out, IlNode.Arg arg) throws IOException {
		switch (arg) {
			case IlNode.Arg.Null ignored -> out.writeByte(0);
			case IlNode.Arg.Default defaults -> {
				out.writeByte(1);
				writeExprInfo(out, defaults.exprInfo());
			}
			case IlNode.Arg.Value value -> {
				out.writeByte(2);
				out.writeInt(value.time());
				writeNode(out, value.node());
			}
		}
	}

	private static IlNode.Arg readArg(DataInputStream in) throws IOException {
		return switch (in.readByte()) {
			case 0 -> new IlNode.Arg.Null();
			case 1 -> new IlNode.Arg.Default(readExprInfo(in));
			case 2 -> {
				int time = in.readInt();
				yield new IlNode.Arg.Value(readNode(in), time);
			}
			default -> throw new IOException("Unknown IL argument type");
		};
	}

	private static void writeExprInfo(DataOutputStream out, IlNode.IlExprInfo info) throws IOException {
		writeStrings(out, info.classNames());
		writeStrings(out, info.isPlural().stream().map(String::valueOf).toList());
		out.writeBoolean(info.isOptional());
		out.writeInt(info.flagMask());
		out.writeInt(info.time());
	}

	private static IlNode.IlExprInfo readExprInfo(DataInputStream in) throws IOException {
		List<String> classNames = readStrings(in);
		List<String> plurals = readStrings(in);
		List<Boolean> isPlural = new ArrayList<>(plurals.size());
		for (String plural : plurals)
			isPlural.add(Boolean.parseBoolean(plural));
		return new IlNode.IlExprInfo(classNames, isPlural, in.readBoolean(), in.readInt(), in.readInt());
	}

	/*
	 * Primitive helpers
	 */

	private static void writeString(DataOutputStream out, @Nullable String string) throws IOException {
		if (string == null) {
			out.writeInt(-1);
			return;
		}
		byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static String readString(DataInputStream in) throws IOException {
		String string = readNullableString(in);
		if (string == null)
			throw new IOException("Null string where a non-null one is required");
		return string;
	}

	private static @Nullable String readNullableString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0)
			return null;
		if (length > 16 * 1024 * 1024)
			throw new IOException("String too long");
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void writeStrings(DataOutputStream out, List<@Nullable String> strings) throws IOException {
		out.writeInt(strings.size());
		for (String string : strings)
			writeString(out, string);
	}

	private static List<String> readStrings(DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > 1024 * 1024)
			throw new IOException("String list too long");
		List<String> strings = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
			strings.add(readNullableString(in));
		return strings;
	}

	private static void writeInts(DataOutputStream out, List<Integer> ints) throws IOException {
		out.writeInt(ints.size());
		for (int value : ints)
			out.writeInt(value);
	}

	private static List<Integer> readInts(DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > 1024 * 1024)
			throw new IOException("Integer list too long");
		List<Integer> ints = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
			ints.add(in.readInt());
		return ints;
	}

	/**
	 * Version written into every cache file.
	 */
	static final String VERSION = ch.njol.skript.Skript.getVersion() + "/" + FORMAT_VERSION;

}
