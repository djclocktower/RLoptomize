package rlperf.dh;

import java.util.regex.Pattern;

/**
 * Shader packs write their Distant Horizons programs against Minecraft's built-in vertex inputs
 * (gl_Vertex, gl_Color, ...). LOD geometry arrives in Distant Horizons' own packed format, so the
 * built-ins are redirected to values decoded from it. Same mapping Iris uses (DHTerrainTransformer),
 * done with macros instead of a GLSL parser.
 */
public final class DhShaderPatch {
    private DhShaderPatch() {}

    /** Added to every program so packs switch on their Distant Horizons code paths. */
    public static final String MACROS = "#define DISTANT_HORIZONS\n"
            + "#define DH_BLOCK_UNKNOWN 0\n#define DH_BLOCK_LEAVES 1\n#define DH_BLOCK_STONE 2\n#define DH_BLOCK_WOOD 3\n"
            + "#define DH_BLOCK_METAL 4\n#define DH_BLOCK_DIRT 5\n#define DH_BLOCK_LAVA 6\n#define DH_BLOCK_DEEPSLATE 7\n"
            + "#define DH_BLOCK_SNOW 8\n#define DH_BLOCK_SAND 9\n#define DH_BLOCK_TERRACOTTA 10\n#define DH_BLOCK_NETHER_STONE 11\n"
            + "#define DH_BLOCK_WATER 12\n#define DH_BLOCK_GRASS 13\n#define DH_BLOCK_AIR 14\n#define DH_BLOCK_ILLUMINATED 15\n";

    private static final String SHARED =
            "uniform mat4 iris_ModelViewMatrix;\n"
            + "uniform mat4 iris_ProjectionMatrix;\n"
            + "uniform mat3 iris_NormalMatrix;\n"
            + "const mat4 dh_textureMatrix[2] = mat4[2](mat4(1.0), mat4(1.0));\n"
            + "#define gl_ModelViewMatrix iris_ModelViewMatrix\n"
            + "#define gl_ProjectionMatrix iris_ProjectionMatrix\n"
            + "#define gl_ModelViewProjectionMatrix (iris_ProjectionMatrix * iris_ModelViewMatrix)\n"
            + "#define gl_NormalMatrix iris_NormalMatrix\n"
            + "#define gl_TextureMatrix dh_textureMatrix\n";

    private static final String VERTEX =
            "in uvec4 vPosition;\n"
            + "in vec4 iris_color;\n"
            + "in uvec4 irisExtra;\n"
            + "uniform float mircoOffset;\n"
            + "uniform vec3 modelOffset;\n"
            + "vec3 _vert_position;\n"
            + "vec2 _vert_tex_light_coord;\n"
            + "int dhMaterialId;\n"
            + "vec4 _vert_color;\n"
            + "vec3 _vert_normal;\n"
            + "const vec3 irisNormals[6] = vec3[6](vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0));\n"
            + "void _vert_init() {\n"
            + "    uint meta = vPosition.a;\n"
            + "    uint mirco = (meta & 0xFF00u) >> 8u;\n"       // 2 bits each for x, y, z: 0b01 = +offset, 0b11 = -offset
            + "    float mx = (mirco & 1u) != 0u ? mircoOffset : 0.0;\n"
            + "    mx = (mirco & 2u) != 0u ? -mx : mx;\n"
            + "    float mz = (mirco & 16u) != 0u ? mircoOffset : 0.0;\n"
            + "    mz = (mirco & 32u) != 0u ? -mz : mz;\n"
            + "    uint lights = meta & 0xFFu;\n"
            + "    _vert_position = vec3(vPosition.xyz) + vec3(mx, 0.0, mz);\n"
            + "    _vert_normal = irisNormals[irisExtra.y];\n"
            + "    dhMaterialId = int(irisExtra.x);\n"
            + "    _vert_tex_light_coord = vec2((float(lights / 16u) + 0.5) / 16.0, (mod(float(lights), 16.0) + 0.5) / 16.0);\n"
            + "    _vert_color = iris_color;\n"
            + "}\n"
            + "vec4 dh_vertex() { return vec4(modelOffset + _vert_position, 1.0); }\n"
            + "#define gl_Vertex dh_vertex()\n"
            + "#define gl_Color _vert_color\n"
            + "#define gl_Normal _vert_normal\n"
            + "#define gl_MultiTexCoord0 vec4(0.0, 0.0, 0.0, 1.0)\n"
            + "#define gl_MultiTexCoord1 vec4(_vert_tex_light_coord, 0.0, 1.0)\n"
            + "#define gl_MultiTexCoord2 vec4(_vert_tex_light_coord, 0.0, 1.0)\n";

    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(void)?\\s*\\)\\s*\\{");

    public static String patch(String source, boolean vertex) {
        int version = source.indexOf("#version");
        int afterVersion = version < 0 ? 0 : source.indexOf('\n', version) + 1;
        String prelude = SHARED + (vertex ? VERTEX : "");
        StringBuilder out = new StringBuilder(source.length() + prelude.length() + 32);
        out.append(source, 0, afterVersion).append(prelude);
        String rest = source.substring(afterVersion);
        // Packs keep both stages in one file behind #ifdef, so there can be several main()s; only one is compiled.
        if (vertex) rest = MAIN.matcher(rest).replaceAll("$0 _vert_init();");
        return out.append(rest).toString();
    }
}
