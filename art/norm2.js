// 以“头部面积”统一人物大小，以“头部质心”对齐，输出同一画布
const sharp=require('sharp'); const fs=require('fs'); const path=require('path');
const CUT='/root/Documents/deepseek-harness/default-workspace/mole-native/art/chibi/cut3';
const RES='/root/Documents/deepseek-harness/default-workspace/mole-native/app/src/main/res/drawable-nodpi';
const NAMES=['happy','angry','cry','dizzy'];
const BOX={happy:[0,0,560,569],angry:[6,0,554,542],cry:[19,15,541,527],dizzy:[13,8,520,543]};

function analyze(data,w,h,b){
  const [bx,by,bw,bh]=b;
  let area=0,sx=0,sy=0;
  const yEnd=by+Math.round(bh*0.45);
  for(let y=by;y<yEnd;y++)for(let x=bx;x<bx+bw;x++){
    const a=data[(y*w+x)*4+3];
    if(a>128){area++;sx+=x*a;sy+=y*a;}
  }
  let sA=0; for(let y=by;y<yEnd;y++)for(let x=bx;x<bx+bw;x++) sA+=data[(y*w+x)*4+3];
  return {area, cx:sx/sA, cy:sy/sA, minX:bx, maxX:bx+bw, minY:by, maxY:by+bh};
}

(async()=>{
  const infos=[];
  for(const n of NAMES){
    const f=path.join(CUT,n+'.png');
    const m=await sharp(f).metadata();
    const {data}=await sharp(f).ensureAlpha().raw().toBuffer({resolveWithObject:true});
    const a=analyze(data,m.width,m.height,BOX[n]);
    infos.push({n,f,w:m.width,h:m.height,...a});
    console.log(n,'头面积',a.area,'头心',a.cx.toFixed(0)+','+a.cy.toFixed(0));
  }
  const A=infos.reduce((s,i)=>s+i.area,0)/infos.length;
  for(const i of infos) i.k=Math.sqrt(A/i.area);

  let maxL=0,maxR=0,maxT=0,maxB=0;
  for(const i of infos){
    i.L=(i.cx-i.minX)*i.k; i.R=(i.maxX-i.cx)*i.k;
    i.T=(i.cy-i.minY)*i.k; i.B=(i.maxY-i.cy)*i.k;
    maxL=Math.max(maxL,i.L); maxR=Math.max(maxR,i.R);
    maxT=Math.max(maxT,i.T); maxB=Math.max(maxB,i.B);
  }
  const PAD=24;
  const CW=Math.round(maxL+maxR+PAD*2), CH=Math.round(maxT+maxB+PAD*2);
  const HX=maxL+PAD, HY=maxT+PAD;   // 头部质心在画布中的位置
  console.log('画布',CW+'x'+CH,'头心位置',HX.toFixed(0)+','+HY.toFixed(0));
  for(const i of infos){
    const nw=Math.round(i.w*i.k), nh=Math.round(i.h*i.k);
    const scaled=await sharp(i.f).resize(nw,nh,{kernel:'lanczos3'}).png().toBuffer();
    const left=Math.round(HX-i.cx*i.k), top=Math.round(HY-i.cy*i.k);
    await sharp({create:{width:CW,height:CH,channels:4,background:{r:0,g:0,b:0,alpha:0}}})
      .composite([{input:scaled,left:Math.max(0,left),top:Math.max(0,top)}])
      .png({compressionLevel:9}).toFile(path.join(RES,'char_'+i.n+'.png'));
    console.log('->',i.n,'k',i.k.toFixed(3),'放到',left+','+top,'尺寸',nw+'x'+nh);
  }
  fs.writeFileSync('/root/Documents/deepseek-harness/default-workspace/mole-native/art/canon.json',
    JSON.stringify({CW,CH,HX,HY}));
})();
