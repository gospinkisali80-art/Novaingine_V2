package com.novaingine.v2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Imports self-contained glTF 2.0 binary files into editable Meshy3D mesh datablocks. */
final class Meshy3DGlbImporter {
    static final class ImportedMesh {
        final String name;
        final Meshy3DCore mesh;

        ImportedMesh(String name, Meshy3DCore mesh) {
            this.name = name;
            this.mesh = mesh;
        }
    }

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final int MAX_VERTICES = 500_000;
    private static final int MAX_FACES = 500_000;

    private final JSONObject document;
    private final byte[] binary;
    private int totalVertices;
    private int totalFaces;

    private Meshy3DGlbImporter(JSONObject document, byte[] binary) {
        this.document = document;
        this.binary = binary;
    }

    static List<ImportedMesh> parse(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length < 20) throw new IllegalArgumentException("The selected file is too small to be a GLB.");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt(0) != GLB_MAGIC) throw new IllegalArgumentException("Invalid GLB magic header.");
        int version = input.getInt(4);
        long declaredLength = Integer.toUnsignedLong(input.getInt(8));
        if (version != 2) throw new IllegalArgumentException("Only glTF Binary version 2 is supported.");
        if (declaredLength > bytes.length || declaredLength < 20) throw new IllegalArgumentException("The GLB container length is invalid.");

        JSONObject json = null;
        byte[] bin = null;
        int cursor = 12;
        while (cursor + 8 <= declaredLength) {
            long chunkLength = Integer.toUnsignedLong(input.getInt(cursor));
            int chunkType = input.getInt(cursor + 4);
            cursor += 8;
            if (chunkLength > declaredLength - cursor) throw new IllegalArgumentException("A GLB chunk extends beyond the file.");
            int end = cursor + (int) chunkLength;
            if (chunkType == JSON_CHUNK && json == null) {
                String text = new String(bytes, cursor, (int) chunkLength, StandardCharsets.UTF_8).trim();
                json = new JSONObject(text);
            } else if (chunkType == BIN_CHUNK && bin == null) {
                bin = new byte[(int) chunkLength];
                System.arraycopy(bytes, cursor, bin, 0, (int) chunkLength);
            }
            cursor = end;
        }
        if (json == null || bin == null) throw new IllegalArgumentException("The GLB must contain JSON and binary chunks.");
        JSONArray buffers = json.optJSONArray("buffers");
        if (buffers == null || buffers.length() == 0) throw new IllegalArgumentException("The GLB has no binary buffer.");
        JSONObject buffer = buffers.getJSONObject(0);
        if (!buffer.isNull("uri")) throw new IllegalArgumentException("External glTF buffers are not supported; choose a self-contained .glb file.");
        long required = buffer.optLong("byteLength", -1);
        if (required < 0 || required > bin.length) throw new IllegalArgumentException("The GLB binary buffer is incomplete.");

        Meshy3DGlbImporter importer = new Meshy3DGlbImporter(json, bin);
        List<ImportedMesh> result = importer.importScene();
        if (result.isEmpty()) throw new IllegalArgumentException("No supported triangle mesh primitives were found in this GLB.");
        return result;
    }

    private List<ImportedMesh> importScene() throws Exception {
        ArrayList<ImportedMesh> output = new ArrayList<>();
        JSONArray nodes = document.optJSONArray("nodes");
        JSONArray meshes = document.optJSONArray("meshes");
        if (meshes == null || meshes.length() == 0) throw new IllegalArgumentException("The GLB contains no mesh data.");
        if (nodes == null || nodes.length() == 0) {
            for (int i = 0; i < meshes.length(); i++) {
                JSONObject mesh = meshes.getJSONObject(i);
                appendMesh(output, mesh, mesh.optString("name", "Imported Mesh " + (i + 1)), identity());
            }
            return output;
        }

        Set<Integer> childNodes = new HashSet<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONArray children = nodes.getJSONObject(i).optJSONArray("children");
            if (children != null) for (int j = 0; j < children.length(); j++) childNodes.add(children.getInt(j));
        }

        JSONArray roots = null;
        JSONArray scenes = document.optJSONArray("scenes");
        int activeScene = document.optInt("scene", 0);
        if (scenes != null && activeScene >= 0 && activeScene < scenes.length()) {
            roots = scenes.getJSONObject(activeScene).optJSONArray("nodes");
        }
        if (roots == null || roots.length() == 0) {
            roots = new JSONArray();
            for (int i = 0; i < nodes.length(); i++) if (!childNodes.contains(i)) roots.put(i);
        }
        boolean[] visited = new boolean[nodes.length()];
        for (int i = 0; i < roots.length(); i++) {
            int index = roots.getInt(i);
            if (index < 0 || index >= nodes.length()) throw new IllegalArgumentException("A scene root references an invalid node.");
            visitNode(index, identity(), nodes, meshes, visited, output, 0);
        }
        return output;
    }

    private void visitNode(int index, float[] parent, JSONArray nodes, JSONArray meshes,
                           boolean[] visited, List<ImportedMesh> output, int depth) throws Exception {
        if (depth > 256) throw new IllegalArgumentException("The GLB node hierarchy is too deep.");
        if (index < 0 || index >= nodes.length()) throw new IllegalArgumentException("A node references an invalid child.");
        if (visited[index]) return;
        visited[index] = true;
        JSONObject node = nodes.getJSONObject(index);
        float[] world = multiply(parent, nodeTransform(node));
        int meshIndex = node.optInt("mesh", -1);
        if (meshIndex >= 0) {
            if (meshIndex >= meshes.length()) throw new IllegalArgumentException("A node references an invalid mesh.");
            JSONObject mesh = meshes.getJSONObject(meshIndex);
            String baseName = node.optString("name", mesh.optString("name", "Imported Mesh " + (meshIndex + 1)));
            appendMesh(output, mesh, baseName, world);
        }
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++)
                visitNode(children.getInt(i), world, nodes, meshes, visited, output, depth + 1);
        }
    }

    private void appendMesh(List<ImportedMesh> output, JSONObject mesh, String baseName, float[] transform) throws Exception {
        JSONArray primitives = mesh.optJSONArray("primitives");
        if (primitives == null) return;
        int primitiveNumber = 0;
        for (int p = 0; p < primitives.length(); p++) {
            JSONObject primitive = primitives.getJSONObject(p);
            int mode = primitive.optInt("mode", 4);
            if (mode != 4 && mode != 5 && mode != 6) continue;
            JSONObject attributes = primitive.optJSONObject("attributes");
            if (attributes == null || !attributes.has("POSITION")) continue;
            JSONObject positionAccessor = document.getJSONArray("accessors").getJSONObject(attributes.getInt("POSITION"));
            float[] positions = readPositions(positionAccessor, transform);
            int vertexCount = positions.length / 3;
            int[] indices;
            if (primitive.has("indices")) {
                JSONObject indexAccessor = document.getJSONArray("accessors").getJSONObject(primitive.getInt("indices"));
                indices = readIndices(indexAccessor);
            } else {
                indices = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) indices[i] = i;
            }
            ArrayList<int[]> triangles = triangulate(indices, mode);
            if (triangles.isEmpty()) continue;
            totalVertices += vertexCount;
            totalFaces += triangles.size();
            if (totalVertices > MAX_VERTICES || totalFaces > MAX_FACES)
                throw new IllegalArgumentException("The GLB exceeds Meshy3D's safe mobile import limit (500,000 vertices or faces).");

            JSONArray vertexArray = new JSONArray();
            for (int i = 0; i < vertexCount; i++) {
                JSONArray point = new JSONArray();
                point.put(positions[i * 3]).put(positions[i * 3 + 1]).put(positions[i * 3 + 2]);
                vertexArray.put(point);
            }
            JSONArray faceArray = new JSONArray();
            for (int[] triangle : triangles) {
                if (triangle[0] >= vertexCount || triangle[1] >= vertexCount || triangle[2] >= vertexCount)
                    throw new IllegalArgumentException("A GLB index points outside its vertex array.");
                faceArray.put(new JSONArray().put(triangle[0]).put(triangle[1]).put(triangle[2]));
            }
            JSONObject state = new JSONObject();
            state.put("vertices", vertexArray);
            state.put("faces", faceArray);
            Meshy3DCore core = new Meshy3DCore();
            if (!core.importState(state)) throw new IllegalArgumentException("A mesh primitive could not be converted to editable geometry.");
            applyMaterial(core, primitive);
            primitiveNumber++;
            String name = primitives.length() > 1 ? baseName + " " + primitiveNumber : baseName;
            output.add(new ImportedMesh(name, core));
        }
    }

    private float[] readPositions(JSONObject accessor, float[] transform) throws Exception {
        if (!"VEC3".equals(accessor.optString("type")) || accessor.optInt("componentType") != 5126)
            throw new IllegalArgumentException("Mesh positions must use float VEC3 accessors.");
        float[] values = readFloatAccessor(accessor, 3);
        for (int i = 0; i < values.length; i += 3) {
            float x = values[i], y = values[i + 1], z = values[i + 2];
            values[i] = transform[0] * x + transform[4] * y + transform[8] * z + transform[12];
            values[i + 1] = transform[1] * x + transform[5] * y + transform[9] * z + transform[13];
            values[i + 2] = transform[2] * x + transform[6] * y + transform[10] * z + transform[14];
            if (!finite(values[i]) || !finite(values[i + 1]) || !finite(values[i + 2]))
                throw new IllegalArgumentException("The GLB contains invalid vertex coordinates.");
        }
        return values;
    }

    private float[] readFloatAccessor(JSONObject accessor, int components) throws Exception {
        int count = accessor.getInt("count");
        if (count < 1 || count > MAX_VERTICES) throw new IllegalArgumentException("A GLB position accessor has an invalid vertex count.");
        if (accessor.has("sparse")) throw new IllegalArgumentException("Sparse glTF accessors are not supported yet.");
        JSONObject view = document.getJSONArray("bufferViews").getJSONObject(accessor.getInt("bufferView"));
        int viewOffset = view.optInt("byteOffset", 0);
        int stride = view.optInt("byteStride", components * 4);
        int start = viewOffset + accessor.optInt("byteOffset", 0);
        if (stride < components * 4) throw new IllegalArgumentException("A GLB position buffer stride is invalid.");
        float[] result = new float[count * components];
        ByteBuffer data = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int offset = start + i * stride;
            if (offset < 0 || offset + components * 4 > binary.length) throw new IllegalArgumentException("A GLB position accessor exceeds the binary buffer.");
            for (int c = 0; c < components; c++) result[i * components + c] = data.getFloat(offset + c * 4);
        }
        return result;
    }

    private int[] readIndices(JSONObject accessor) throws Exception {
        if (!"SCALAR".equals(accessor.optString("type"))) throw new IllegalArgumentException("GLB indices must use SCALAR accessors.");
        if (accessor.has("sparse")) throw new IllegalArgumentException("Sparse glTF index accessors are not supported yet.");
        int componentType = accessor.getInt("componentType");
        int bytesPerIndex = componentType == 5121 ? 1 : componentType == 5123 ? 2 : componentType == 5125 ? 4 : 0;
        if (bytesPerIndex == 0) throw new IllegalArgumentException("Unsupported GLB index component type.");
        int count = accessor.getInt("count");
        if (count < 3 || count > MAX_FACES * 3) throw new IllegalArgumentException("A GLB index accessor has an invalid count.");
        JSONObject view = document.getJSONArray("bufferViews").getJSONObject(accessor.getInt("bufferView"));
        int start = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0);
        int stride = view.optInt("byteStride", bytesPerIndex);
        if (stride < bytesPerIndex) throw new IllegalArgumentException("A GLB index buffer stride is invalid.");
        ByteBuffer data = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int offset = start + i * stride;
            if (offset < 0 || offset + bytesPerIndex > binary.length) throw new IllegalArgumentException("A GLB index accessor exceeds the binary buffer.");
            if (componentType == 5121) result[i] = data.get(offset) & 0xFF;
            else if (componentType == 5123) result[i] = data.getShort(offset) & 0xFFFF;
            else {
                long value = Integer.toUnsignedLong(data.getInt(offset));
                if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("A GLB index is too large.");
                result[i] = (int) value;
            }
        }
        return result;
    }

    private static ArrayList<int[]> triangulate(int[] indices, int mode) {
        ArrayList<int[]> triangles = new ArrayList<>();
        if (mode == 4) {
            for (int i = 0; i + 2 < indices.length; i += 3)
                if (indices[i] != indices[i + 1] && indices[i + 1] != indices[i + 2] && indices[i] != indices[i + 2])
                    triangles.add(new int[]{indices[i], indices[i + 1], indices[i + 2]});
        } else if (mode == 5) {
            for (int i = 2; i < indices.length; i++) {
                int a = indices[i - 2], b = indices[i - 1], c = indices[i];
                if ((i & 1) != 0) { int swap = a; a = b; b = swap; }
                if (a != b && b != c && a != c) triangles.add(new int[]{a, b, c});
            }
        } else {
            for (int i = 2; i < indices.length; i++) {
                int a = indices[0], b = indices[i - 1], c = indices[i];
                if (a != b && b != c && a != c) triangles.add(new int[]{a, b, c});
            }
        }
        return triangles;
    }

    private void applyMaterial(Meshy3DCore mesh, JSONObject primitive) {
        try {
            int materialIndex = primitive.optInt("material", -1);
            JSONArray materials = document.optJSONArray("materials");
            if (materials == null || materialIndex < 0 || materialIndex >= materials.length()) return;
            JSONObject pbr = materials.getJSONObject(materialIndex).optJSONObject("pbrMetallicRoughness");
            if (pbr == null) return;
            JSONArray color = pbr.optJSONArray("baseColorFactor");
            int argb = 0xFFFFFFFF;
            if (color != null && color.length() >= 3) {
                int red = Math.round(clamp((float) color.optDouble(0, 1)) * 255);
                int green = Math.round(clamp((float) color.optDouble(1, 1)) * 255);
                int blue = Math.round(clamp((float) color.optDouble(2, 1)) * 255);
                argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
            float metallic = (float) pbr.optDouble("metallicFactor", 1);
            float roughness = (float) pbr.optDouble("roughnessFactor", 1);
            mesh.setMaterial(argb, metallic, roughness);
        } catch (Exception ignored) {}
    }

    private static float[] nodeTransform(JSONObject node) throws Exception {
        JSONArray matrix = node.optJSONArray("matrix");
        if (matrix != null) {
            if (matrix.length() != 16) throw new IllegalArgumentException("A glTF node matrix must have 16 values.");
            float[] result = new float[16];
            for (int i = 0; i < 16; i++) result[i] = (float) matrix.getDouble(i);
            return result;
        }
        JSONArray t = node.optJSONArray("translation"), r = node.optJSONArray("rotation"), s = node.optJSONArray("scale");
        float tx = t == null ? 0 : (float) t.optDouble(0, 0);
        float ty = t == null ? 0 : (float) t.optDouble(1, 0);
        float tz = t == null ? 0 : (float) t.optDouble(2, 0);
        float x = r == null ? 0 : (float) r.optDouble(0, 0);
        float y = r == null ? 0 : (float) r.optDouble(1, 0);
        float z = r == null ? 0 : (float) r.optDouble(2, 0);
        float w = r == null ? 1 : (float) r.optDouble(3, 1);
        float sx = s == null ? 1 : (float) s.optDouble(0, 1);
        float sy = s == null ? 1 : (float) s.optDouble(1, 1);
        float sz = s == null ? 1 : (float) s.optDouble(2, 1);
        float norm = (float) Math.sqrt(x*x + y*y + z*z + w*w);
        if (norm < 1e-8f) throw new IllegalArgumentException("A glTF node has an invalid rotation quaternion.");
        x /= norm; y /= norm; z /= norm; w /= norm;
        float[] m = identity();
        m[0]=(1-2*y*y-2*z*z)*sx; m[1]=(2*x*y+2*w*z)*sx; m[2]=(2*x*z-2*w*y)*sx;
        m[4]=(2*x*y-2*w*z)*sy; m[5]=(1-2*x*x-2*z*z)*sy; m[6]=(2*y*z+2*w*x)*sy;
        m[8]=(2*x*z+2*w*y)*sz; m[9]=(2*y*z-2*w*x)*sz; m[10]=(1-2*x*x-2*y*y)*sz;
        m[12]=tx; m[13]=ty; m[14]=tz;
        return m;
    }

    private static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 4; row++)
                out[column * 4 + row] = a[row] * b[column * 4]
                        + a[4 + row] * b[column * 4 + 1]
                        + a[8 + row] * b[column * 4 + 2]
                        + a[12 + row] * b[column * 4 + 3];
        return out;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value) && Math.abs(value) <= 100000; }
}
