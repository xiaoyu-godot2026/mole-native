const sharp=require('sharp'); const fs=require('fs'); const path=require('path');
const CUT='/root/Documents/deepseek-harness/default-workspace/mole-native/art/chibi/cut3';
const RES='/root/Documents/deepseek-harness/default-workspace/mole-native/app/src/main/res/drawable-nodpi';
const MAP={happy:'char_happy',angry:'char_angry',cry:'char_cry',dizzy:'char_dizzy'};
(async()=>{
  for(const [k,v] of Object.entries(MAP)){
    await sharp(path.join(CUT,k+'.png')).png({compressionLevel:9}).toFile(path.join(RES,v+'.png'));
  }
  // 铁盆三种颜色
  const base=path.join(CUT,'basin.png');
  const m=await sharp(base).metadata();
  const {data,info}=await sharp(base).removeAlpha().raw().toBuffer({resolveWithObject:true});
  const N=info.width*info.height;
  const tints={grey:null,red:[235,72,72],blue:[70,120,235],green:[60,175,110]};
  for(const [name,t] of Object.entries(tints)){
    const out=Buffer.alloc(N*3);
    for(let i=0;i<N;i++){
      const r=data[i*3],g=data[i*3+1],b=data[i*3+2];
      if(!t){out[i*3]=r;out[i*3+1]=g;out[i*3+2]=b;continue;}
      const lum=(r+g+b)/3;
      const sp=Math.max(0,lum-232); // 保留高光
      const f=t.map(c=>Math.min(255,c*lum/255*1.62+sp*1.1));
      out[i*3]=f[0];out[i*3+1]=f[1];out[i*3+2]=f[2];
    }
    const rgb=await sharp(out,{raw:{width:info.width,height:info.height,channels:3}}).png().toBuffer();
    const a=await sharp(base).ensureAlpha().extractChannel(3).raw().toBuffer();
    await sharp(rgb).joinChannel(a,{raw:{width:info.width,height:info.height,channels:1}}).png({compressionLevel:9})
      .toFile(path.join(RES,'basin_'+name+'.png'));
  }
  // 头部位置探测
  for(const [k,v] of Object.entries(MAP)){
    const f=path.join(RES,v+'.png');
    const mm=await sharp(f).metadata();
    const {data:d,info:inf}=await sharp(f).ensureAlpha().raw().toBuffer({resolveWithObject:true});
    const w=inf.width,h=inf.height;
    let headTop=-1, firstRow=-1;
    for(let y=0;y<h;y++){
      let run=0,best=0;
      for(let x=0;x<w;x++){ if(d[(y*w+x)*4+3]>60){run++;if(run>best)best=run;}else run=0; }
      if(firstRow<0&&best>3) firstRow=y;
      if(headTop<0&&best>w*0.22){headTop=y;break;}
    }
    // 该行左右范围
    console.log(`${k}: ${w}x${h} 首行Y=${firstRow} 头带顶Y=${headTop} (${(headTop/h*100).toFixed(1)}%)`);
  }
  const bm=await sharp(path.join(RES,'basin_grey.png')).metadata();
  console.log('basin',bm.width+'x'+bm.height);
})();
