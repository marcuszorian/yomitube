# Regenerate: python3 -I app/src/fork/make_icons.py app/src/fork/res  (needs Pillow + Noto Sans CJK)
"""Draws the YomiTube symbol (user's design): blue rounded square with 読 + small play arrow,
green/teal cards fanned behind, star wand at top right. Rendered at 4x then downscaled."""
import sys, os, math
from PIL import Image, ImageDraw, ImageFont
FONT="/usr/share/fonts/google-noto-sans-cjk-vf-fonts/NotoSansCJK-VF.ttc"
BLUE=(0x1F,0x56,0xD6,255); BLUE2=(0x23,0x5F,0xE0,255)
GREEN=(0x4C,0xBB,0x6C,255); TEAL=(0x2A,0x9D,0xA8,255)
INK=(0x33,0x40,0x52,255); WHITE=(255,255,255,255)

def font(size, weight="Bold"):
    f=ImageFont.truetype(FONT,size,index=0)
    try: f.set_variation_by_name(weight)
    except Exception: pass
    return f

def rot_rect(d_img, cx, cy, w, h, r, angle, fill):
    """Rounded rect rotated by `angle` degrees around its centre."""
    s=int(max(w,h)*1.6)
    tile=Image.new("RGBA",(s,s),(0,0,0,0))
    ImageDraw.Draw(tile).rounded_rectangle([(s-w)/2,(s-h)/2,(s+w)/2,(s+h)/2],radius=r,fill=fill)
    tile=tile.rotate(angle,resample=Image.BICUBIC)
    d_img.alpha_composite(tile,(int(cx-s/2),int(cy-s/2)))

def star(cx, cy, R, r, rot=-90):
    pts=[]
    for i in range(10):
        a=math.radians(rot+i*36); rad=R if i%2==0 else r
        pts.append((cx+rad*math.cos(a), cy+rad*math.sin(a)))
    return pts

def symbol(S):
    """Full-colour symbol on a transparent SxS canvas, filling roughly 0.10..0.92 of it."""
    im=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
    u=S/100.0
    # cards fanned behind (left-to-right: green, teal, blue)
    rot_rect(im, 46*u, 37*u, 26*u, 34*u, 4*u, 8, GREEN)
    rot_rect(im, 54*u, 34*u, 26*u, 34*u, 4*u, 2, TEAL)
    rot_rect(im, 63*u, 33*u, 26*u, 34*u, 4*u, -6, BLUE2)
    # main tile
    x0,y0,x1,y1=14*u,28*u,72*u,86*u
    d.rounded_rectangle([x0,y0,x1,y1],radius=11*u,fill=BLUE)
    # 読
    f=font(int(34*u))
    l,t,r,b=d.textbbox((0,0),"読",font=f)
    gx=x0+(x1-x0)*0.43-(r-l)/2-l; gy=(y0+y1)/2-(b-t)/2-t
    d.text((gx,gy),"読",font=f,fill=WHITE)
    # small play arrow to the right of the glyph
    px=x0+(x1-x0)*0.80; py=(y0+y1)/2
    d.polygon([(px-3.2*u,py-4.2*u),(px-3.2*u,py+4.2*u),(px+4.0*u,py)],fill=WHITE)
    # wand: stick then star (outlined) at top right
    sx,sy=79*u,30*u
    d.line([(sx+2*u,sy+3*u),(93*u,54*u)],fill=INK,width=int(3.4*u))
    pts=star(sx,sy,10.5*u,4.6*u)
    d.polygon(pts,fill=INK)
    d.polygon(star(sx,sy,7.4*u,3.2*u),fill=BLUE)
    return im

def glyph_white(S, scale):
    im=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
    f=font(int(S*scale*1.15))
    l,t,r,b=d.textbbox((0,0),"読",font=f)
    d.text(((S-(r-l))/2-l,(S-(b-t))/2-t),"読",font=f,fill=WHITE)
    return im

def down(im, s): return im.resize((s,s),Image.LANCZOS)

def centred(im):
    """Crops to the drawn content and re-centres it on a square canvas with a small margin."""
    box=im.getbbox(); art=im.crop(box)
    side=int(max(art.size)*1.04)
    out=Image.new("RGBA",(side,side),(0,0,0,0))
    out.alpha_composite(art,((side-art.width)//2,(side-art.height)//2))
    return out

if __name__=="__main__":
    OUT=sys.argv[1]
    big=centred(symbol(1600)).resize((1600,1600),Image.LANCZOS)
    dens={"mdpi":1,"hdpi":1.5,"xhdpi":2,"xxhdpi":3,"xxxhdpi":4}
    for name,k in dens.items():
        # adaptive foreground: 108dp, art kept inside the central 66dp safe zone
        fs=int(108*k); fg=Image.new("RGBA",(fs,fs),(0,0,0,0))
        inner=int(fs*0.66); fg.alpha_composite(down(big,inner),((fs-inner)//2,(fs-inner)//2))
        os.makedirs(f"{OUT}/mipmap-{name}",exist_ok=True); fg.save(f"{OUT}/mipmap-{name}/ic_launcher_foreground.png")
        # legacy launcher icon: symbol on light rounded tile
        s=int(48*k); ic=Image.new("RGBA",(s,s),(0,0,0,0))
        ImageDraw.Draw(ic).rounded_rectangle([0,0,s-1,s-1],radius=int(s*.22),fill=(0xF4,0xF6,0xFB,255))
        ic.alpha_composite(down(big,int(s*0.92)),(int(s*.04),int(s*.04)))
        ic.save(f"{OUT}/mipmap-{name}/ic_launcher.png")
        # notification / status-bar icons must be flat white silhouettes
        n=int(24*k)
        os.makedirs(f"{OUT}/drawable-{name}",exist_ok=True)
        g=down(glyph_white(400,0.80),n)
        g.save(f"{OUT}/drawable-{name}/ic_newpipe_triangle_white.png")
        g.save(f"{OUT}/drawable-{name}/ic_newpipe_update.png")
    # splash: full-colour symbol
    os.makedirs(f"{OUT}/drawable-nodpi",exist_ok=True)
    down(big,400).save(f"{OUT}/drawable-nodpi/yomitube_splash_glyph.png")
    # previews
    p=Image.new("RGBA",(432,432),(0xF4,0xF6,0xFB,255)); p.alpha_composite(down(big,int(432*0.66)),(73,73)); p.save("/tmp/yomi_icon/preview_logo.png")
    ImageDraw.Draw(p)
    # circle-masked preview (how a round launcher crops it)
    m=Image.new("L",(432,432),0); ImageDraw.Draw(m).ellipse([0,0,431,431],fill=255)
    c=Image.new("RGBA",(432,432),(0,0,0,0)); c.paste(p,(0,0),m); c.save("/tmp/yomi_icon/preview_round.png")
