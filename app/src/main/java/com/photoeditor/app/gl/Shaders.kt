package com.photoeditor.app.gl

/**
 * GLSL ES 3.00. Один прохід обробляє все: геометрію (через матрицю вихід→джерело),
 * баланс білого, експозицію, тони, контраст, насиченість, HSL, локальні маски,
 * тонування (color grading), вицвітання, віньєтку і зерно.
 *
 * Порядок операцій близький до Lightroom: спершу у лінійному світлі (баланс білого, експозиція),
 * далі в перцептивному (гамма ~2.2) — тонові та кольорові корекції.
 */
object Shaders {
    const val MAX_MASKS = 4

    val VERTEX = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        uniform float uFlipY;
        uniform vec4 uOutRect;   // частина результату, що малюється (x, y, w, h) — для експорту смугами
        out vec2 vOut;
        void main() {
            vec2 base = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5 * uFlipY);
            vOut = uOutRect.xy + base * uOutRect.zw;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;
        precision highp int;

        in vec2 vOut;
        out vec4 fragColor;

        uniform sampler2D uTex;
        uniform sampler2D uMask0;
        uniform sampler2D uMask1;
        uniform sampler2D uMask2;
        uniform sampler2D uMask3;
        uniform sampler2D uDetail;     // фрагмент оригіналу в повній роздільності (при збільшенні)
        uniform sampler2D uCoverage;   // де замість фрагмента брати прев'ю (області AI-ластика)
        uniform vec4 uDetailRect;      // область фрагмента в uv джерела: (l, t, w, h)
        uniform float uDetailOn;
        uniform sampler2D uCurve;      // криві: 256×1, канали R/G/B
        uniform float uCurveOn;
        uniform float uClipWarn;

        uniform mat3 uXform;      // вихідні uv -> uv джерела (проєктивно)
        uniform vec2 uImgScale;   // (W, H) / max(W, H) — у «квадратні» одиниці
        uniform vec2 uOutSize;    // розмір результату в пікселях джерела
        uniform vec3 uBg;
        uniform float uBypass;    // 1 = показати оригінал (порівняння)
        uniform float uSplit;     // шторка «до/після»: ліворуч від x (uv результату) — оригінал; < 0 — вимкнено

        uniform float uExposure, uBrightness, uContrast;
        uniform float uHighlights, uShadows, uWhites, uBlacks;
        uniform float uTemp, uTint, uSaturation, uVibrance;
        uniform float uVignette, uGrain, uFade, uSeed;
        uniform float uSharpen, uClarity, uDehaze, uNoise, uColorNoise;
        uniform float uLensK, uLensN, uLensVig, uChromatic;   // корекція об'єктива
        uniform mediump sampler3D uLut;   // імпортований 3D LUT (.cube)
        uniform float uLutAmount, uLutSize;

        uniform vec3 uHsl[8];     // (hue, sat, lum) для 8 діапазонів

        uniform vec3 uGradeS, uGradeM, uGradeH;  // кольорові зсуви (без яскравості)
        uniform vec3 uGradeLum;                  // зсув яскравості для тіней/середніх/світлих
        uniform float uGradeBalance, uGradeBlend;

        uniform int uMaskCount;
        uniform int uMaskType[4];   // 0 градієнт, 1 радіальна, 2 растрова
        uniform vec4 uMaskA[4];
        uniform vec4 uMaskB[4];
        uniform float uMaskInv[4];
        uniform vec4 uMaskAdjA[4];  // експозиція, контраст, світлі, тіні
        uniform vec4 uMaskAdjB[4];  // насиченість, температура, відтінок, -
        uniform int uMaskRangeMode[4]; // 0 — без діапазону, 1 — яскравість, 2 — колір
        uniform vec4 uMaskRange[4];    // яскравість: lo, hi, м'якість; колір: відтінок°, ширина°, м'якість°
        uniform int uShowMask;      // індекс маски для червоного підсвічування або -1

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
        const float HUE_C[8] = float[8](0.0, 30.0, 60.0, 120.0, 180.0, 225.0, 270.0, 315.0);

        float luma(vec3 c) { return dot(c, LUMA); }

        vec3 toLinear(vec3 c) { return pow(max(c, 0.0), vec3(2.2)); }
        vec3 toGamma(vec3 c)  { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }

        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        vec3 hsv2rgb(vec3 c) {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        // Тіні / світлі / білі / чорні — зміна яскравості зі збереженням відтінку
        vec3 tone(vec3 c, float hi, float sh, float wh, float bl) {
            float L = luma(c);
            float Lc = clamp(L, 0.0, 1.6);
            float wS = 1.0 - smoothstep(0.0, 0.55, Lc); wS *= wS;
            float wH = smoothstep(0.45, 1.0, Lc);       wH *= wH;
            float wW = smoothstep(0.7, 1.1, Lc);
            float wB = 1.0 - smoothstep(0.0, 0.3, Lc);
            float d = sh * 0.32 * wS + hi * 0.32 * wH + wh * 0.22 * wW + bl * 0.15 * wB;
            float newL = max(L + d, 0.0);
            vec3 scaled = c * (newL / max(L, 1.0e-4));
            return mix(c + vec3(d), scaled, smoothstep(0.02, 0.12, L));
        }

        vec3 contrast(vec3 c, float k) {
            if (k >= 0.0) {
                vec3 x = clamp(c, 0.0, 1.0);
                vec3 s = x * x * (3.0 - 2.0 * x);
                vec3 lin = (c - 0.5) * (1.0 + 0.35 * k) + 0.5;
                return mix(lin, mix(lin, s, 0.8), k);
            }
            return (c - 0.5) * (1.0 + k * 0.65) + 0.5;
        }

        vec3 saturate3(vec3 c, float s) { return mix(vec3(luma(c)), c, 1.0 + s); }

        vec3 whiteBalance(vec3 c, float t, float ti) {
            vec3 wb = vec3(1.0 + 0.28 * t + 0.08 * ti, 1.0 - 0.18 * ti, 1.0 - 0.28 * t + 0.08 * ti);
            wb /= dot(wb, LUMA);
            return c * wb;
        }

        float hueWeight(float hDeg, int i) {
            int prev = (i + 7) - ((i + 7) / 8) * 8;
            int next = (i + 1) - ((i + 1) / 8) * 8;
            float c = HUE_C[i];
            float left = mod(c - HUE_C[prev] + 360.0, 360.0);
            float right = mod(HUE_C[next] - c + 360.0, 360.0);
            float d = mod(hDeg - c + 540.0, 360.0) - 180.0;
            float w = d < 0.0 ? 1.0 + d / left : 1.0 - d / right;
            return clamp(w, 0.0, 1.0);
        }

        vec3 hslAdjust(vec3 c) {
            vec3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));
            float hDeg = hsv.x * 360.0;
            float dh = 0.0, ds = 0.0, dl = 0.0;
            for (int i = 0; i < 8; i++) {
                float w = hueWeight(hDeg, i);
                dh += w * uHsl[i].x;
                ds += w * uHsl[i].y;
                dl += w * uHsl[i].z;
            }
            if (dh == 0.0 && ds == 0.0 && dl == 0.0) return c;
            float sw = smoothstep(0.02, 0.25, hsv.y);
            hsv.x = fract(hsv.x + dh * sw * (30.0 / 360.0));
            hsv.y = clamp(hsv.y * (1.0 + ds), 0.0, 1.0);
            vec3 outc = hsv2rgb(hsv);
            // Зберігаємо «перевищення» над 1.0, щоб не втрачати пересвічені ділянки
            outc += c - clamp(c, 0.0, 1.0);
            return outc * (1.0 + dl * 0.5 * sw);
        }

        float maskValue(int i, vec2 sq, float bm) {
            float m;
            if (uMaskType[i] == 0) {
                vec2 p0 = uMaskA[i].xy;
                vec2 d = uMaskA[i].zw - p0;
                float t = clamp(dot(sq - p0, d) / max(dot(d, d), 1.0e-6), 0.0, 1.0);
                m = 1.0 - smoothstep(0.0, 1.0, t);
            } else if (uMaskType[i] == 1) {
                vec2 q = sq - uMaskA[i].xy;
                float cs = uMaskB[i].x, sn = uMaskB[i].y;
                q = vec2(cs * q.x + sn * q.y, -sn * q.x + cs * q.y);
                float dd = length(q / max(uMaskA[i].zw, vec2(1.0e-4)));
                float f = clamp(uMaskB[i].z, 0.0, 1.0);
                m = 1.0 - smoothstep(max(1.0 - f, 0.0) - 0.001, 1.0, dd);
            } else {
                m = bm;
            }
            return uMaskInv[i] > 0.5 ? 1.0 - m : m;
        }

        /** Уточнення маски за яскравістю / кольором джерела. */
        float rangeWeight(int i, float L, vec3 hsv) {
            vec4 r = uMaskRange[i];
            if (uMaskRangeMode[i] == 1) {
                return smoothstep(r.x - r.z, r.x + r.z, L) * (1.0 - smoothstep(r.y - r.z, r.y + r.z, L));
            }
            if (uMaskRangeMode[i] == 2) {
                float d = abs(mod(hsv.x * 360.0 - r.x + 540.0, 360.0) - 180.0);
                // Сірі пікселі не мають відтінку — їх колірний діапазон не зачіпає
                return (1.0 - smoothstep(r.y, r.y + r.z, d)) * smoothstep(0.06, 0.2, hsv.y);
            }
            return 1.0;
        }

        vec3 localAdjust(vec3 c, float m, vec4 a, vec4 b) {
            if (m <= 0.0) return c;
            c *= exp2(a.x * m / 2.2);
            c = tone(c, a.z * m, a.w * m, 0.0, 0.0);
            c = contrast(c, a.y * m);
            c = saturate3(c, b.x * m);
            c *= vec3(1.0 + 0.12 * b.y * m + 0.04 * b.z * m, 1.0 - 0.08 * b.z * m, 1.0 - 0.12 * b.y * m + 0.04 * b.z * m);
            return c;
        }

        // Сусідній піксель для різкості / шумозаглушення: з детального тайла, якщо центр береться з нього.
        // textureLod(…, 0) — у розгалуженнях неявні похідні не визначені.
        vec3 tapAt(vec2 uv, bool det) {
            if (det) return textureLod(uDetail, clamp((uv - uDetailRect.xy) / uDetailRect.zw, 0.0, 1.0), 0.0).rgb;
            return textureLod(uTex, uv, 0.0).rgb;
        }

        float maxc(vec3 c) { return max(c.r, max(c.g, c.b)); }
        float minc(vec3 c) { return min(c.r, min(c.g, c.b)); }

        float hash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        void main() {
            vec3 sp = uXform * vec3(vOut, 1.0);
            vec2 suv = sp.xy / sp.z;
            // Дисторсія об'єктива: радіально відносно центру джерела, без порожніх країв (див. GeometryMath.lensN)
            float r2 = dot((suv - 0.5) * uImgScale, (suv - 0.5) * uImgScale) / dot(0.5 * uImgScale, 0.5 * uImgScale);
            if (uLensK != 0.0) suv = 0.5 + (suv - 0.5) * (1.0 + uLensK * r2) / uLensN;
            // Вибірка до розгалужень: для mip-рівнів потрібні коректні похідні
            vec3 base = texture(uTex, clamp(suv, 0.0, 1.0)).rgb;
            if (sp.z <= 0.0 || suv.x < 0.0 || suv.y < 0.0 || suv.x > 1.0 || suv.y > 1.0) {
                fragColor = vec4(uBg, 1.0);
                return;
            }
            vec3 src = base;
            bool inDet = false;
            if (uDetailOn > 0.5) {
                vec2 d = (suv - uDetailRect.xy) / uDetailRect.zw;
                if (d.x >= 0.0 && d.y >= 0.0 && d.x <= 1.0 && d.y <= 1.0) {
                    vec3 det = textureLod(uDetail, d, 0.0).rgb;
                    float cov = textureLod(uCoverage, d, 0.0).r;
                    src = mix(det, src, cov);
                    inDet = cov < 0.5;
                }
            }
            if (uBypass > 0.5 || (uSplit >= 0.0 && vOut.x < uSplit)) {
                fragColor = vec4(src, 1.0);
                return;
            }
            if (uChromatic != 0.0) {
                // Поперечна хроматична аберація: червоний і синій канали масштабуються радіально в різні боки
                vec2 dr = (suv - 0.5) * uChromatic * 0.004;
                src.r = tapAt(suv - dr, inDet).r;
                src.b = tapAt(suv + dr, inDet).b;
            }

            // ---- Деталізація (у просторі джерела). Радіуси задані в «пікселях 2048-px прев'ю»,
            // тож прев'ю та експорт у повній роздільності виглядають однаково
            vec2 texSize = vec2(textureSize(uTex, 0));
            float texMax = max(texSize.x, texSize.y);
            vec2 stepUv = max(1.0 / (2048.0 * uImgScale), 1.0 / texSize);
            vec3 raw = src;
            if (uNoise > 0.0) {
                // Білатеральний фільтр 3×3: згладжує шум, але не перетинає контрастні краї
                float sg = 0.015 + 0.10 * uNoise;
                float inv = 1.0 / (2.0 * sg * sg);
                vec3 acc = src;
                float ws = 1.0;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        if (i == 0 && j == 0) continue;
                        vec3 n = tapAt(suv + vec2(float(i), float(j)) * stepUv, inDet);
                        vec3 dd = n - raw;
                        float w = exp(-dot(dd, dd) * inv) * ((i == 0 || j == 0) ? 1.0 : 0.7);
                        acc += n * w;
                        ws += w;
                    }
                }
                src = mix(src, acc / ws, min(1.0, uNoise * 1.5));
            }
            if (uColorNoise > 0.0) {
                // Кольоровий шум: розмита кольоровість при збереженій яскравості
                float lodCN = log2(max(4.0 * texMax / 2048.0, 3.0)); // не менше ~3 пікселів навіть на малих фото
                vec3 cb = textureLod(uTex, suv, lodCN).rgb;
                float L0 = luma(src);
                src = vec3(L0) + mix(src - vec3(L0), cb - vec3(luma(cb)), uColorNoise);
            }
            if (uSharpen > 0.0) {
                // Нерізке маскування по яскравості (без кольорових ореолів) з порогом, щоб не підсилювати шум
                vec3 b = (tapAt(suv + vec2(stepUv.x, 0.0), inDet) + tapAt(suv - vec2(stepUv.x, 0.0), inDet) +
                          tapAt(suv + vec2(0.0, stepUv.y), inDet) + tapAt(suv - vec2(0.0, stepUv.y), inDet)) * 0.25;
                float dl = luma(raw - b);
                float edge = smoothstep(0.003, 0.02, abs(dl));
                src += vec3(dl * uSharpen * 2.4 * edge);
            }
            // Велике розмиття з mip-рівнів — для чіткості (радіус ~1% кадру)
            vec3 blurC = src;
            if (uClarity != 0.0) {
                float lodC = max(log2(texMax * 0.012) - 0.5, 0.0);
                vec2 r = 0.007 / uImgScale;
                blurC = (2.0 * textureLod(uTex, suv, lodC).rgb +
                         textureLod(uTex, suv + vec2(r.x, r.y), lodC).rgb + textureLod(uTex, suv + vec2(-r.x, r.y), lodC).rgb +
                         textureLod(uTex, suv + vec2(r.x, -r.y), lodC).rgb + textureLod(uTex, suv + vec2(-r.x, -r.y), lodC).rgb) / 6.0;
            }

            // ---- Лінійне світло
            vec3 c = toLinear(src);
            if (uLensVig > 0.0) c *= 1.0 + uLensVig * (0.6 * r2 + 0.5 * r2 * r2);
            if (uDehaze != 0.0) {
                // Спрощений «темний канал»: димка = мінімальний канал сильно розмитого кадру, повітря — середній колір
                float lodD = max(log2(texMax * 0.04), 0.0);
                float dark = minc(toLinear(textureLod(uTex, suv, lodD).rgb));
                vec3 avg = toLinear(textureLod(uTex, vec2(0.5), 20.0).rgb);
                vec3 A = mix(vec3(1.0), avg / max(maxc(avg), 1.0e-3), 0.25) * clamp(maxc(avg) * 1.6, 0.6, 1.0);
                if (uDehaze > 0.0) {
                    float t = clamp(1.0 - 0.95 * uDehaze * dark / max(minc(A), 1.0e-3), 0.25, 1.0);
                    c = (c - A) / t + A;
                } else {
                    c = mix(c, A, -uDehaze * 0.45);
                }
                c = max(c, 0.0);
            }
            c = whiteBalance(c, uTemp, uTint);
            c *= exp2(uExposure);

            // ---- Перцептивний простір
            c = toGamma(c);
            c = tone(c, uHighlights, uShadows, uWhites, uBlacks);
            c = pow(max(c, 0.0), vec3(exp2(-uBrightness * 0.8)));
            c = contrast(c, uContrast);
            if (uClarity != 0.0) {
                // Локальний контраст у середніх тонах
                float Lc = clamp(luma(c), 0.0, 1.0);
                float mid = 1.0 - pow(abs(2.0 * Lc - 1.0), 2.0);
                c += vec3((luma(src) - luma(blurC)) * uClarity * 1.6 * mid);
            }

            // ---- Тонові криві (загальна + канальні зведені в одну таблицю)
            if (uCurveOn > 0.5) {
                vec3 x = clamp(c, 0.0, 1.0);
                vec3 over = c - x;   // пересвіт понад 1.0 зберігаємо
                const float S = 255.0 / 256.0, O = 0.5 / 256.0;
                c = vec3(
                    texture(uCurve, vec2(x.r * S + O, 0.5)).r,
                    texture(uCurve, vec2(x.g * S + O, 0.5)).g,
                    texture(uCurve, vec2(x.b * S + O, 0.5)).b
                ) + over;
            }

            // Насиченість та «розумна» насиченість (менше діє на вже насичені кольори)
            {
                float mx = max(c.r, max(c.g, c.b));
                float mn = min(c.r, min(c.g, c.b));
                float s = clamp(mx - mn, 0.0, 1.0);
                c = saturate3(c, uVibrance * (1.0 - s) * 1.2);
                c = saturate3(c, uSaturation);
            }
            c = hslAdjust(c);

            // ---- Локальні маски
            vec2 sq = suv * uImgScale;
            float bm[4];
            bm[0] = texture(uMask0, suv).r;
            bm[1] = texture(uMask1, suv).r;
            bm[2] = texture(uMask2, suv).r;
            bm[3] = texture(uMask3, suv).r;
            float shown = 0.0;
            float srcL = luma(clamp(src, 0.0, 1.0));
            vec3 srcHsv = rgb2hsv(clamp(src, 0.0, 1.0));
            for (int i = 0; i < 4; i++) {
                if (i >= uMaskCount) break;
                float m = maskValue(i, sq, bm[i]) * rangeWeight(i, srcL, srcHsv);
                c = localAdjust(c, m, uMaskAdjA[i], uMaskAdjB[i]);
                if (i == uShowMask) shown = m;
            }

            // ---- Тонування (color grading)
            {
                float L = clamp(luma(c), 0.0, 1.0);
                float bal = uGradeBalance * 0.2;
                float bw = mix(0.12, 0.4, uGradeBlend);
                float ws = 1.0 - smoothstep(0.33 + bal - bw, 0.33 + bal + bw, L);
                float wh = smoothstep(0.66 + bal - bw, 0.66 + bal + bw, L);
                float wm = clamp(1.0 - ws - wh, 0.0, 1.0);
                c += uGradeS * ws + uGradeM * wm + uGradeH * wh;
                c += vec3(dot(uGradeLum, vec3(ws, wm, wh))) * 0.2;
            }

            // ---- 3D LUT (після тонування, до плівкових ефектів)
            if (uLutAmount > 0.0) {
                vec3 x = clamp(c, 0.0, 1.0);
                vec3 uvw = x * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
                c = mix(c, texture(uLut, uvw).rgb, uLutAmount);
            }

            // ---- Вицвітання (підняті чорні, «матовість»)
            c = c * (1.0 - 0.12 * uFade) + 0.1 * uFade;

            // ---- Віньєтка (у координатах результату)
            {
                float aspect = uOutSize.x / max(uOutSize.y, 1.0);
                vec2 q = (vOut - 0.5) * vec2(aspect, 1.0);
                float d = length(q) / length(vec2(0.5 * aspect, 0.5));
                float v = smoothstep(0.35, 1.05, d);
                if (uVignette < 0.0) c *= 1.0 + uVignette * v * 0.85;
                else c += (1.0 - c) * uVignette * v * 0.6;
            }

            // ---- Зерно (прив'язане до зображення, а не до пікселів екрана)
            if (uGrain > 0.0) {
                float aspect = uOutSize.x / max(uOutSize.y, 1.0);
                vec2 cell = floor(vOut * vec2(aspect, 1.0) * 1100.0);
                float n = hash(cell + uSeed) + hash(cell * 1.7 + 11.0 + uSeed) - 1.0;
                float L = clamp(luma(c), 0.0, 1.0);
                c += n * uGrain * 0.12 * (1.0 - abs(L - 0.5) * 1.2);
            }

            if (uClipWarn > 0.5) {
                if (max(c.r, max(c.g, c.b)) >= 0.999) c = vec3(1.0, 0.1, 0.1);
                else if (max(c.r, max(c.g, c.b)) <= 0.002) c = vec3(0.15, 0.35, 1.0);
            }
            c = clamp(c, 0.0, 1.0);
            if (uShowMask >= 0) c = mix(c, vec3(1.0, 0.15, 0.2), shown * 0.55);
            fragColor = vec4(c, 1.0);
        }
    """.trimIndent()
}
