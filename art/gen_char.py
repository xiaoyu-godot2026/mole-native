# -*- coding: utf-8 -*-
"""生成 Q 版鲸鱼少女精灵图（开心 / 生气）与铁饭盆"""

HAIR_L = "#6FA8FF"
HAIR = "#3E6FE0"
HAIR_D = "#2B4FBF"
SKIN = "#FFE3D1"
SKIN_D = "#F5C4AE"
DRESS = "#26305C"
DRESS_L = "#33417A"
WHITE = "#FFFFFF"
WHITE_D = "#DCE8FF"
BOW = "#4A7BE8"
IRIS = "#2E6BE6"
IRIS_D = "#1B45A8"
BLUSH = "#FFA8B8"

def head_common():
    return f'''
  <!-- 后发 -->
  <ellipse cx="256" cy="186" rx="166" ry="152" fill="{HAIR}"/>
  <ellipse cx="256" cy="160" rx="148" ry="128" fill="{HAIR_L}" opacity="0.45"/>
  <!-- 垂在肩前的两缕 -->
  <path d="M126 180 C112 250 116 322 136 378 C164 350 170 276 164 196 Z" fill="{HAIR}"/>
  <path d="M386 180 C400 250 396 322 376 378 C348 350 342 276 348 196 Z" fill="{HAIR}"/>
  <path d="M140 196 C130 254 134 312 148 348 C160 320 162 258 158 202 Z" fill="{HAIR_L}" opacity="0.45"/>
  <path d="M372 196 C382 254 378 312 364 348 C352 320 350 258 354 202 Z" fill="{HAIR_L}" opacity="0.45"/>
  <!-- 脸 -->
  <ellipse cx="256" cy="198" rx="106" ry="100" fill="{SKIN}"/>
  <ellipse cx="256" cy="214" rx="94" ry="82" fill="#FFEFE6" opacity="0.55"/>
'''

def bangs():
    return f'''
  <!-- 齐刘海 + 三个发尖 -->
  <path d="M146 150 C146 100 198 56 256 56 C314 56 366 100 366 150 Z" fill="{HAIR}"/>
  <path d="M182 148 L204 182 L226 148 Z" fill="{HAIR}"/>
  <path d="M236 148 L256 188 L276 148 Z" fill="{HAIR}"/>
  <path d="M286 148 L308 182 L330 148 Z" fill="{HAIR}"/>
  <!-- 发丝反光 -->
  <path d="M196 96 C210 116 214 138 212 148" stroke="{HAIR_L}" stroke-width="11" opacity="0.55" fill="none" stroke-linecap="round"/>
  <path d="M240 88 C250 110 252 134 250 148" stroke="{HAIR_L}" stroke-width="9" opacity="0.45" fill="none" stroke-linecap="round"/>
  <path d="M300 96 C290 116 286 138 288 148" stroke="{HAIR_L}" stroke-width="10" opacity="0.45" fill="none" stroke-linecap="round"/>
'''

def headband():
    return f'''
  <!-- 女仆头饰：一条蕾丝带贴在发顶 -->
  <path d="M162 122 C190 72 222 54 256 54 C290 54 322 72 350 122"
        stroke="{WHITE}" stroke-width="21" fill="none" stroke-linecap="round"/>
  <g fill="{WHITE}">
    <circle cx="172" cy="112" r="9"/><circle cx="192" cy="90" r="9"/>
    <circle cx="216" cy="72" r="10"/><circle cx="244" cy="62" r="10"/>
    <circle cx="268" cy="62" r="10"/><circle cx="296" cy="72" r="10"/>
    <circle cx="320" cy="90" r="9"/><circle cx="340" cy="112" r="9"/>
  </g>
  <!-- 蝴蝶结 -->
  <g transform="translate(348 126) rotate(16)">
    <path d="M0 0 C-26 -19 -42 4 -21 14 C-8 20 0 10 0 0 Z" fill="{BOW}"/>
    <path d="M0 0 C26 -19 42 4 21 14 C8 20 0 10 0 0 Z" fill="{BOW}"/>
    <circle cx="0" cy="5" r="7" fill="{HAIR_D}"/>
  </g>
  <!-- 月牙发饰 -->
  <g transform="translate(176 116)">
    <path d="M0 0 a15 15 0 1 0 13 24 a11 11 0 1 1 -13 -24 z" fill="{WHITE}"/>
  </g>
'''

def eyes_happy():
    return f'''
  <!-- 眼睛（开心） -->
  <g>
    <ellipse cx="214" cy="214" rx="32" ry="36" fill="{WHITE}"/>
    <ellipse cx="298" cy="214" rx="32" ry="36" fill="{WHITE}"/>
    <ellipse cx="216" cy="216" rx="22" ry="27" fill="{IRIS}"/>
    <ellipse cx="300" cy="216" rx="22" ry="27" fill="{IRIS}"/>
    <ellipse cx="216" cy="224" rx="16" ry="17" fill="{IRIS_D}"/>
    <ellipse cx="300" cy="224" rx="16" ry="17" fill="{IRIS_D}"/>
    <circle cx="216" cy="217" r="9" fill="#0F1E3D"/>
    <circle cx="300" cy="217" r="9" fill="#0F1E3D"/>
    <circle cx="208" cy="204" r="8" fill="#FFFFFF"/>
    <circle cx="292" cy="204" r="8" fill="#FFFFFF"/>
    <circle cx="224" cy="228" r="4.5" fill="#FFFFFF" opacity="0.9"/>
    <circle cx="308" cy="228" r="4.5" fill="#FFFFFF" opacity="0.9"/>
  </g>
  <!-- 嘴（笑） -->
  <path d="M238 266 q18 22 36 0" stroke="#C4715F" stroke-width="6" fill="none" stroke-linecap="round"/>
  <path d="M244 270 q12 12 24 0 z" fill="#E8798E"/>
  <!-- 腮红 -->
  <ellipse cx="180" cy="252" rx="19" ry="11" fill="{BLUSH}" opacity="0.55"/>
  <ellipse cx="332" cy="252" rx="19" ry="11" fill="{BLUSH}" opacity="0.55"/>
'''

def eyes_angry():
    return f'''
  <!-- 眼睛（生气 ><） -->
  <path d="M192 200 L232 218 L192 236" stroke="{HAIR_D}" stroke-width="19" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M320 200 L280 218 L320 236" stroke="{HAIR_D}" stroke-width="19" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  <!-- 嘴（嘟嘴） -->
  <path d="M240 274 q16 -12 32 0 q-16 10 -32 0 z" fill="#E8798E"/>
  <!-- 腮红 -->
  <ellipse cx="180" cy="256" rx="21" ry="12" fill="{BLUSH}" opacity="0.8"/>
  <ellipse cx="332" cy="256" rx="21" ry="12" fill="{BLUSH}" opacity="0.8"/>
  <!-- 怒气符号 -->
  <g stroke="#FF5A5A" stroke-width="7" fill="none" stroke-linecap="round" opacity="0.95">
    <path d="M150 118 L166 134 M166 118 L150 134"/>
    <path d="M346 118 L362 134 M362 118 L346 134"/>
  </g>
'''

def body():
    return f'''
  <!-- 身体：海军蓝女仆裙 + 白围裙 -->
  <path d="M186 300 C160 312 146 340 142 372 L130 420 L382 420 L370 372 C366 340 352 312 326 300 Z" fill="{DRESS}"/>
  <path d="M258 300 L254 420 L382 420 L370 372 C366 340 352 312 326 300 Z" fill="{DRESS_L}" opacity="0.55"/>
  <path d="M206 322 C188 340 180 372 178 420 L334 420 C332 372 324 340 306 322 Z" fill="{WHITE}"/>
  <path d="M206 322 C222 338 290 338 306 322 C300 316 212 316 206 322 Z" fill="{WHITE_D}"/>
  <path d="M256 322 L256 420" stroke="{WHITE_D}" stroke-width="4" opacity="0.8"/>
  <!-- 领口与蝴蝶结 -->
  <path d="M228 296 q28 18 56 0 q-28 -14 -56 0 z" fill="{WHITE}"/>
  <g transform="translate(256 312)">
    <path d="M0 0 C-20 -14 -32 4 -16 12 C-6 17 0 8 0 0 Z" fill="{BOW}"/>
    <path d="M0 0 C20 -14 32 4 16 12 C6 17 0 8 0 0 Z" fill="{BOW}"/>
    <circle cx="0" cy="5" r="6" fill="{HAIR_D}"/>
  </g>
  <!-- 手臂 -->
  <path d="M182 316 C160 344 152 380 154 408 C168 414 182 402 186 380 Z" fill="{DRESS}"/>
  <path d="M330 316 C352 344 360 380 358 408 C344 414 330 402 326 380 Z" fill="{DRESS}"/>
  <path d="M158 396 q14 12 28 2 q-2 18 -14 22 q-14 -2 -14 -24 z" fill="{SKIN}"/>
  <path d="M354 396 q-14 12 -28 2 q2 18 14 22 q14 -2 14 -24 z" fill="{SKIN}"/>
  <!-- 鲸鱼尾 -->
  <path d="M348 404 C392 386 430 358 452 322 C466 346 468 380 452 404
           C464 414 468 428 462 440 C440 428 400 420 356 420 Z" fill="{HAIR_L}"/>
  <path d="M348 404 C392 386 430 358 452 322 C462 338 466 356 462 372
           C436 386 396 400 356 412 Z" fill="{HAIR}" opacity="0.55"/>
'''

def svg(face):
    face_svg = eyes_happy() if face == "happy" else eyes_angry()
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 420" width="512" height="420">
{head_common()}
{bangs()}
{headband()}
{face_svg}
{body()}
</svg>
'''

basin = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 160" width="256" height="160">
  <defs>
    <linearGradient id="m" x1="0" y1="0" x2="0.2" y2="1">
      <stop offset="0" stop-color="#E9EEF4"/><stop offset="0.45" stop-color="#B9C4CF"/>
      <stop offset="1" stop-color="#7C8896"/>
    </linearGradient>
    <linearGradient id="rim" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#F3F7FB"/><stop offset="1" stop-color="#A9B5C2"/>
    </linearGradient>
  </defs>
  <!-- 盆身 -->
  <path d="M18 62 C18 62 26 132 128 132 C230 132 238 62 238 62 Z" fill="url(#m)"/>
  <!-- 盆口 -->
  <ellipse cx="128" cy="62" rx="110" ry="34" fill="url(#rim)"/>
  <ellipse cx="128" cy="64" rx="96" ry="26" fill="#7E8B99"/>
  <ellipse cx="128" cy="72" rx="96" ry="22" fill="#9FAAB6"/>
  <!-- 高光 -->
  <path d="M52 78 C60 110 92 122 128 122" stroke="#FFFFFF" stroke-width="9" fill="none" opacity="0.65" stroke-linecap="round"/>
  <path d="M168 100 q26 -14 34 -34" stroke="#FFFFFF" stroke-width="6" fill="none" opacity="0.4" stroke-linecap="round"/>
  <!-- 小凹痕 -->
  <ellipse cx="186" cy="104" rx="12" ry="7" fill="#6E7A87" opacity="0.5"/>
</svg>
'''

open('char_happy.svg', 'w', encoding='utf-8').write(svg('happy'))
open('char_angry.svg', 'w', encoding='utf-8').write(svg('angry'))
open('basin.svg', 'w', encoding='utf-8').write(basin)
print('svg written')
