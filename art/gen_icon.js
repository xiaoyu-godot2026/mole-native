const sharp=require('sharp'); const path=require('path'); const fs=require('fs');
const RES='/root/Documents/deepseek-harness/default-workspace/mole-native/app/src/main/res';
const SRC='/root/.dsh/attachments/v1/objects/80/8032b94c73fe3efed07bfe4d3fbc8f2f1765eb2018643b884cf8b41b1ef145f0';
const LO=26,HI=62;
(async()=>{
  const m=await sharp(SRC).metadata();
  const {data,info}=await sharp(SRC).raw().toBuffer({resolveWithObject:true});
  const w=info.width,h=info.height,ch=info.channels,N=w*h;
  const px=(x,y)=>{const i=(y*w+x)*ch;return[data[i],data[i+1],data[i+2]];};
  const cs=[[4,4],[w-5,4],[4,h-5],[w-5,h-5],[w>>1,4],[4,h>>1]].map(([x,y])=>px(x,y));
  const br=cs.reduce((s,c)=>s+c[0],0)/6, bg=cs.reduce((s,c)=>s+c[1],0)/6, bb=cs.reduce((s,c)=>s+c[2],0)/6;
  const rgba=Buffer.alloc(N*4);
  let minX=1e9,maxX=-1,minY=1e9,maxY=-1;
  for(let i=0;i<N;i++){
    const r=data[i*ch],g=data[i*ch+1],b=data[i*ch+2];
    let a=(HI-(g-(r+b)/2))/(HI-LO); a=a<0?0:a>1?1:a;
    if(a<0.06){rgba[i*4+3]=0;continue;}
    const ad=Math.max(a,0.34),ia=1/ad, cl=v=>v<0?0:v>255?255:v;
    let fr=cl((r-(1-a)*br)*ia), fg=cl((g-(1-a)*bg)*ia), fb=cl((b-(1-a)*bb)*ia);
    const lim=(fr+fb)/2+8; if(fg>lim) fg=lim;
    rgba[i*4]=fr;rgba[i*4+1]=fg;rgba[i*4+2]=fb;rgba[i*4+3]=Math.round(a*255);
    const x=i%w,y=(i/w)|0;
    if(x<minX)minX=x;if(x>maxX)maxX=x;if(y<minY)minY=y;if(y>maxY)maxY=y;
  }
  const cut=await sharp(rgba,{raw:{width:w,height:h,channels:4}}).png().toBuffer();
  const bbox={left:minX,top:minY,width:maxX-minX+1,height:maxY-minY+1};
  fs.writeFileSync('/root/Documents/deepseek-harness/default-workspace/mole-native/art/icon_cut.png',cut);
  console.log('裁切',JSON.stringify(bbox));

  // 背景渐变（浅蓝 → 深蓝）
  const GRAD=await sharp({create:{width:512,height:512,channels:4,background:{r:0,g:0,b:0,alpha:0}}})
    .composite([{input:Buffer.from(
      `<svg width="512" height="512"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
       <stop offset="0%" stop-color="#9BD9F5"/><stop offset="55%" stop-color="#4FA6DB"/><stop offset="100%" stop-color="#1E6FAE"/>
       </linearGradient></defs><rect width="512" height="512" fill="url(#g)"/></svg>`),left:0,top:0}])
    .png().toBuffer();

  async function square(size, round){
    const inner=Math.round(size*0.94);
    const ch2=await sharp(cut).extract(bbox).resize({height:Math.round(size*0.92),fit:'inside'}).toBuffer();
    const cm=await sharp(ch2).metadata();
    const left=Math.round((size-cm.width)/2), top=Math.round((size-cm.height)*0.55);
    let base=await sharp(GRAD).resize(size,size).toBuffer();
    let img=await sharp(base).composite([{input:ch2,left:Math.max(0,left),top:Math.max(0,top)}]).png().toBuffer();
    if(round){
      const mask=Buffer.from(`<svg width="${size}" height="${size}"><circle cx="${size/2}" cy="${size/2}" r="${size/2-1}" fill="#fff"/></svg>`);
      img=await sharp(img).composite([{input:mask,blend:'dest-in'}]).png().toBuffer();
    }
    return img;
  }
  const dens={mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192};
  for(const [k,s] of Object.entries(dens)){
    const dir=path.join(RES,'mipmap-'+k); fs.mkdirSync(dir,{recursive:true});
    fs.writeFileSync(path.join(dir,'ic_launcher.png'),await square(s,false));
    fs.writeFileSync(path.join(dir,'ic_launcher_round.png'),await square(s,true));
  }
  // 自适应前景（432，内容留在安全区）
  const fgH=Math.round(432*0.62);
  const fgc=await sharp(cut).extract(bbox).resize({height:fgH,fit:'inside'}).toBuffer();
  const fm=await sharp(fgc).metadata();
  await sharp({create:{width:432,height:432,channels:4,background:{r:0,g:0,b:0,alpha:0}}})
    .composite([{input:fgc,left:Math.round((432-fm.width)/2),top:Math.round((432-fm.height)/2)}])
    .png({compressionLevel:9}).toFile(path.join(RES,'mipmap-xxxhdpi','ic_launcher_foreground.png'));
  console.log('icon ok');
})();
