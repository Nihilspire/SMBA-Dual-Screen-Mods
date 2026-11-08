/* topgpu.c - the top screen on the GPU (0.3). 0.1 and 0.2 scaled the picture on the CPU, pixel by pixel into the framebuffer, which on a real 3DS held 3:2 under 60 fps
 * where the smaller integer scale nearly reached it. Now the CPU only hands over a 240x160 picture: the GPU's transfer engine puts it in a texture and one quad scales it,
 * at any size and with real bilinear filtering for SMOOTH SCALING, as mGBA's own 3DS port does. The borders are a second texture, uploaded only when they change, and the FPS
 * and message lines a third. The bottom screen stays a plain framebuffer that main.c writes. */
#include <3ds.h>
#include <citro3d.h>
#include <string.h>
#include "topgpu.h"

extern const u8 quad_shbin[];
extern const u32 quad_shbin_size;

static C3D_RenderTarget *target;
static DVLB_s *dvlb;
static shaderProgram_s program;
static int uProj;
static C3D_Mtx proj;
static C3D_Tex texGame, texBorder, texText;
static uint16_t *linBorder, *linText;
static int borderReady, textDirty, smoothNow = -1;

#define OUT_FLAGS (GX_TRANSFER_IN_FORMAT(GX_TRANSFER_FMT_RGB565) | GX_TRANSFER_OUT_FORMAT(GX_TRANSFER_FMT_RGB565))
/* GX wants a linear source and hands the texture its tiled layout. Flipped, because the GPU's rows run from the bottom. */
static void upload(C3D_Tex *t, void *lin, int w, int h, size_t bytes) {
    GSPGPU_FlushDataCache(lin, bytes);
    C3D_SyncDisplayTransfer((u32 *)lin, GX_BUFFER_DIM(w, h), (u32 *)t->data, GX_BUFFER_DIM(t->width, t->height), OUT_FLAGS | GX_TRANSFER_OUT_TILED(1) | GX_TRANSFER_FLIP_VERT(1));
}
int tg_init(void) {
    if (!C3D_Init(C3D_DEFAULT_CMDBUF_SIZE)) return 0;
    target = C3D_RenderTargetCreate(240, 400, GPU_RB_RGB565, -1);
    if (!target) return 0;
    C3D_RenderTargetSetOutput(target, GFX_TOP, GFX_LEFT, OUT_FLAGS);
    if (!C3D_TexInitVRAM(&texGame, 256, 256, GPU_RGB565) || !C3D_TexInitVRAM(&texBorder, 512, 256, GPU_RGB565) || !C3D_TexInitVRAM(&texText, 256, 32, GPU_RGB565)) return 0;
    linBorder = linearAlloc(512 * 240 * 2);
    linText = linearAlloc(256 * 32 * 2);
    if (!linBorder || !linText) return 0;
    memset(linBorder, 0, 512 * 240 * 2); memset(linText, 0, 256 * 32 * 2);
    dvlb = DVLB_ParseFile((u32 *)quad_shbin, quad_shbin_size);
    shaderProgramInit(&program);
    shaderProgramSetVsh(&program, &dvlb->DVLE[0]);
    C3D_BindProgram(&program);
    uProj = shaderInstanceGetUniformLocation(program.vertexShader, "projection");
    C3D_AttrInfo *a = C3D_GetAttrInfo();
    AttrInfo_Init(a);
    AttrInfo_AddLoader(a, 0, GPU_FLOAT, 3);
    AttrInfo_AddLoader(a, 1, GPU_FLOAT, 2);
    Mtx_OrthoTilt(&proj, 0.0f, 400.0f, 240.0f, 0.0f, 0.0f, 1.0f, true);       /* screen pixels, y down */
    C3D_TexEnv *env = C3D_GetTexEnv(0);
    C3D_TexEnvInit(env);
    C3D_TexEnvSrc(env, C3D_Both, GPU_TEXTURE0, 0, 0);
    C3D_TexEnvFunc(env, C3D_Both, GPU_REPLACE);
    C3D_DepthTest(false, GPU_ALWAYS, GPU_WRITE_COLOR);
    C3D_CullFace(GPU_CULL_NONE);
    C3D_TexSetFilter(&texBorder, GPU_NEAREST, GPU_NEAREST);
    C3D_TexSetFilter(&texText, GPU_NEAREST, GPU_NEAREST);
    C3D_TexSetWrap(&texGame, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    C3D_TexSetWrap(&texBorder, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    C3D_TexSetWrap(&texText, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    return 1;
}
void tg_fini(void) {
    if (!target) return;
    C3D_TexDelete(&texGame); C3D_TexDelete(&texBorder); C3D_TexDelete(&texText);
    shaderProgramFree(&program); DVLB_Free(dvlb);
    linearFree(linBorder); linearFree(linText);
    C3D_RenderTargetDelete(target);
    C3D_Fini();
    target = NULL;
}
void tg_border(const uint16_t *b400) {
    for (int y = 0; y < 240; y++) memcpy(linBorder + y * 512, b400 + y * 400, 400 * 2);
    upload(&texBorder, linBorder, 512, 240, 512 * 240 * 2);
    borderReady = 1;
}
void tg_game(void *lin256) { upload(&texGame, lin256, 256, 160, 256 * 160 * 2); }
void tg_text(int row, const uint16_t *pixels, int w) {
    for (int y = 0; y < TG_TEXT_H; y++) memcpy(linText + (row * 16 + y) * 256, pixels + y * 256, (size_t)w * 2);
    textDirty = 1;
}
/* x0 y0 x1 y1 on the screen, then the texture's texels: the flipped transfer leaves the picture's top row at v = 0 */
static void quad(const C3D_Tex *t, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
    float w = t->width, h = t->height;
    u0 /= w; u1 /= w; v0 /= h; v1 /= h;
    C3D_ImmDrawBegin(GPU_TRIANGLE_STRIP);
    C3D_ImmSendAttrib(x0, y0, 0.5f, 0.0f); C3D_ImmSendAttrib(u0, v0, 0.0f, 0.0f);
    C3D_ImmSendAttrib(x0, y1, 0.5f, 0.0f); C3D_ImmSendAttrib(u0, v1, 0.0f, 0.0f);
    C3D_ImmSendAttrib(x1, y0, 0.5f, 0.0f); C3D_ImmSendAttrib(u1, v0, 0.0f, 0.0f);
    C3D_ImmSendAttrib(x1, y1, 0.5f, 0.0f); C3D_ImmSendAttrib(u1, v1, 0.0f, 0.0f);
    C3D_ImmDrawEnd();
}
void tg_present(const TgFrame *f) {
    if (textDirty) { upload(&texText, linText, 256, 32, 256 * 32 * 2); textDirty = 0; }
    C3D_FrameBegin(0);
    C3D_RenderTargetClear(target, C3D_CLEAR_COLOR, 0x000000FF, 0);
    C3D_FrameDrawOn(target);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, uProj, &proj);
    if (borderReady) {
        C3D_TexBind(0, &texBorder);
        quad(&texBorder, 0, 0, 400, 240, 0, 0, 400, 240);
    }
    if (f->game) {
        if (smoothNow != f->smooth) { smoothNow = f->smooth; C3D_TexSetFilter(&texGame, f->smooth ? GPU_LINEAR : GPU_NEAREST, f->smooth ? GPU_LINEAR : GPU_NEAREST); }
        C3D_TexBind(0, &texGame);
        /* half a texel in when filtered: past the picture's edge is whatever was there */
        float e = f->smooth ? 0.5f : 0.0f;
        quad(&texGame, f->x, f->y, f->x + f->w, f->y + f->h, e, e, 240 - e, 160 - e);
    }
    for (int i = 0; i < 2; i++) if (f->textW[i] > 0) {
        C3D_TexBind(0, &texText);
        quad(&texText, f->textX[i], f->textY[i], f->textX[i] + f->textW[i], f->textY[i] + TG_TEXT_H, 0, i * 16, f->textW[i], i * 16 + TG_TEXT_H);
    }
    /* GX_CMDLIST_FLUSH: flush the command list only. Without it citro3d flushes all of linear memory, every frame. */
    C3D_FrameEnd(GX_CMDLIST_FLUSH);
}
