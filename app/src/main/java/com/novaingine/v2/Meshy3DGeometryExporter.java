package com.novaingine.v2;

import java.util.ArrayList;
import java.util.List;

/** Geometry-only interchange exporters. Object transforms are baked into exported vertices. */
final class Meshy3DGeometryExporter {
    static final class ObjectSnapshot {
        final String name;
        final float[][] vertices;
        final int[][] faces;
        final double[] world;

        ObjectSnapshot(String name, float[][] vertices, int[][] faces, double[] world) {
            this.name = name;
            this.vertices = vertices;
            this.faces = faces;
            this.world = world;
        }
    }

    private static final int MAX_VERTICES = 500_000;
    private static final int MAX_TRIANGLES = 500_000;
    private static final int MAX_TEXT_CHARS = 80 * 1024 * 1024;

    static List<ObjectSnapshot> snapshot(Meshy3DObjectScene scene) {
        if (scene == null) throw new IllegalStateException("The Meshy3D scene is not available.");
        ArrayList<ObjectSnapshot> result = new ArrayList<>();
        long vertices = 0, faces = 0;
        for (Meshy3DObjectScene.Object3D object : scene.getObjects()) {
            if (object.mesh == null) continue;
            float[][] meshVertices = object.mesh.copyVertices();
            int[][] meshFaces = object.mesh.copyFaces();
            if (meshVertices.length == 0 || meshFaces.length == 0) continue;
            vertices += meshVertices.length;
            faces += meshFaces.length;
            if (vertices > MAX_VERTICES || faces > MAX_TRIANGLES)
                throw new IllegalArgumentException("Export is limited to 500,000 vertices and faces for mobile memory safety.");
            result.add(new ObjectSnapshot(object.name, meshVertices, meshFaces,
                    scene.getWorldMatrixCopy(object.id)));
        }
        if (result.isEmpty()) throw new IllegalStateException("There are no mesh objects to export.");
        return result;
    }

    static String exportObj(List<ObjectSnapshot> objects) {
        StringBuilder out = new StringBuilder();
        out.append("# Meshy3D Wavefront OBJ export\n");
        int vertexBase = 1;
        for (ObjectSnapshot object : objects) {
            out.append("\no ").append(safeName(object.name)).append('\n');
            float[][] worldVertices = transformVertices(object);
            for (float[] vertex : worldVertices) {
                out.append("v ").append(Float.toString(vertex[0])).append(' ')
                        .append(Float.toString(vertex[1])).append(' ')
                        .append(Float.toString(vertex[2])).append('\n');
                checkSize(out);
            }
            out.append("s off\n");
            for (int[] face : object.faces) {
                if (face == null || face.length < 3) continue;
                out.append('f');
                for (int index : face) {
                    if (index < 0 || index >= worldVertices.length)
                        throw new IllegalArgumentException("A mesh face references an invalid vertex.");
                    out.append(' ').append(vertexBase + index);
                }
                out.append('\n');
                checkSize(out);
            }
            vertexBase += worldVertices.length;
        }
        return out.toString();
    }

    static String exportAsciiStl(List<ObjectSnapshot> objects) {
        StringBuilder out = new StringBuilder();
        out.append("solid meshy3d\n");
        int triangles = 0;
        for (ObjectSnapshot object : objects) {
            float[][] vertices = transformVertices(object);
            for (int[] face : object.faces) {
                if (face == null || face.length < 3) continue;
                for (int corner = 1; corner + 1 < face.length; corner++) {
                    if (++triangles > MAX_TRIANGLES)
                        throw new IllegalArgumentException("STL export exceeds the 500,000 triangle mobile limit.");
                    float[] a = getVertex(vertices, face[0]);
                    float[] b = getVertex(vertices, face[corner]);
                    float[] c = getVertex(vertices, face[corner + 1]);
                    float[] normal = normal(a, b, c);
                    out.append(" facet normal ").append(Float.toString(normal[0])).append(' ')
                            .append(Float.toString(normal[1])).append(' ')
                            .append(Float.toString(normal[2])).append("\n  outer loop\n");
                    appendVertex(out, a);
                    appendVertex(out, b);
                    appendVertex(out, c);
                    out.append("  endloop\n endfacet\n");
                    checkSize(out);
                }
            }
        }
        out.append("endsolid meshy3d\n");
        return out.toString();
    }

    private static float[][] transformVertices(ObjectSnapshot object) {
        float[][] result = new float[object.vertices.length][3];
        double[] m = object.world;
        for (int i = 0; i < result.length; i++) {
            float[] p = object.vertices[i];
            double x = m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3];
            double y = m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7];
            double z = m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11];
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 100000 || Math.abs(y) > 100000 || Math.abs(z) > 100000)
                throw new IllegalArgumentException("An object transform produced invalid export coordinates.");
            result[i][0] = (float) x;
            result[i][1] = (float) y;
            result[i][2] = (float) z;
        }
        return result;
    }

    private static float[] getVertex(float[][] vertices, int index) {
        if (index < 0 || index >= vertices.length)
            throw new IllegalArgumentException("A mesh face references an invalid vertex.");
        return vertices[index];
    }

    private static float[] normal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1e-12f) { nx /= length; ny /= length; nz /= length; }
        else { nx = 0; ny = 0; nz = 0; }
        return new float[]{nx, ny, nz};
    }

    private static void appendVertex(StringBuilder out, float[] p) {
        out.append("   vertex ").append(Float.toString(p[0])).append(' ')
                .append(Float.toString(p[1])).append(' ')
                .append(Float.toString(p[2])).append('\n');
    }

    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "Mesh";
        return name.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private static void checkSize(StringBuilder out) {
        if (out.length() > MAX_TEXT_CHARS)
            throw new IllegalArgumentException("The text export exceeds the 80 MB mobile file limit.");
    }
}
