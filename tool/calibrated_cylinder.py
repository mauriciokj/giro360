#!/usr/bin/env python3
"""Experimental gravity-aligned cylindrical projection from ARKit K and R.

Coordinates: stored JPEG is native camera image rotated 90 deg clockwise.
ARKit transform is row-major camera-to-world, camera axes right/up/backward.
No translation/depth correction is claimed. Warps use only recorded pixels.
"""
import argparse
import json
import math
import time
from pathlib import Path
import cv2
import numpy as np


def render(timeline, output, seam, width=5190):
    start = time.perf_counter()
    root = json.loads(timeline.read_text())
    frames = sorted(root['frames'], key=lambda f:f['binIndex'])
    inputs = {timeline.resolve()} | {(timeline.parent / Path(f['filePath']).name).resolve() for f in frames}
    if output.resolve() in inputs:
        raise ValueError('Output must not overwrite an input')
    height=width//2
    focal=width/math.tau
    step=width/len(frames)
    half=int(math.ceil(max(step*1.15, focal*math.radians(8))))
    margin=half+8
    # Covers the captured vertical field; unobserved pixels stay masked/black.
    top=max(0,height//2-round(focal*1.2));bottom=min(height,height//2+round(focal*1.2))
    pano_h=bottom-top
    world_cv=np.diag([1.,-1.,-1.])
    portrait_to_arkit=np.array([[0.,1.,0.],[1.,0.,0.],[0.,0.,-1.]])
    images=[]; masks=[]; corners=[]; weights=[]
    offsets=[]
    for frame in frames:
        r=np.array(frame['cameraTransform']).reshape(4,4)[:3,:3]
        offsets.append(math.atan2(-r[0,2],r[2,2])-frame['relativeYawRadians'])
    yaw_offset=math.atan2(np.mean(np.sin(offsets)),np.mean(np.cos(offsets)))
    for frame in frames:
        image=cv2.imread(str(timeline.parent/Path(frame['filePath']).name))
        if image is None: raise RuntimeError('Missing image')
        ih,iw=image.shape[:2]
        k=np.array(frame['cameraIntrinsics']).reshape(3,3)
        # Native dimensions are landscape; JPEG dimensions are portrait.
        kp=np.array([[k[1,1],0,iw-1-k[1,2]],[0,k[0,0],k[0,2]],[0,0,1.]])
        r=world_cv@np.array(frame['cameraTransform']).reshape(4,4)[:3,:3]@portrait_to_arkit
        center=(frame['relativeYawRadians']%math.tau)/math.tau*width
        centers=[center]
        if center<half:centers.append(center+width)
        if center>width-half:centers.append(center-width)
        for center in centers:
            left=int(round(center))-half
            xs=np.arange(left,left+2*half+1,dtype=np.float32)
            ys=np.arange(top,bottom,dtype=np.float32)
            theta=xs/focal+yaw_offset
            vx=np.broadcast_to(np.sin(theta)[None,:],(len(ys),len(xs)))
            vy=np.broadcast_to(((ys-height/2)/focal)[:,None],vx.shape)
            vz=np.broadcast_to(np.cos(theta)[None,:],vx.shape)
            ray=np.stack([vx,vy,vz],axis=-1)@r
            z=ray[:,:,2]
            mx=(kp[0,0]*ray[:,:,0]/z+kp[0,2]).astype(np.float32)
            my=(kp[1,1]*ray[:,:,1]/z+kp[1,2]).astype(np.float32)
            mask=((z>0)&(mx>=1)&(mx<iw-2)&(my>=1)&(my<ih-2)).astype(np.uint8)*255
            patch=cv2.remap(image,mx,my,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
            # Retain actual horizontal geometry; soft edges only in the overlap.
            w=np.maximum(0,1-np.abs(xs-center)/half)**2
            weight=mask.astype(np.float32)/255*w[None,:]
            corner=(left+margin,0)
            images.append(patch);masks.append(mask);corners.append(corner);weights.append(weight)
    canvas_width=width+2*margin
    if seam=='graphcut':
        seam_masks=[cv2.UMat(m) for m in masks]
        finder=cv2.detail_GraphCutSeamFinder('COST_COLOR_GRAD')
        found=finder.find([p.astype(np.float32) for p in images],corners,seam_masks)
        found=list(found) if found is not None else seam_masks
        weights=[]
        for new,original in zip(found,masks):
            m=new.get() if hasattr(new,'get') else new
            softened=cv2.GaussianBlur(m.astype(np.float32)/255,(0,0),1.5)
            weights.append(softened*(original>0))
    acc=np.zeros((pano_h,canvas_width,3),np.float32)
    weight_sum=np.zeros((pano_h,canvas_width),np.float32)
    for patch,weight,corner in zip(images,weights,corners):
        left=corner[0];right=left+patch.shape[1]
        a=max(0,left);b=min(canvas_width,right)
        if b<=a:continue
        sl=slice(a-left,b-left)
        acc[:,a:b]+=patch[:,sl].astype(np.float32)*weight[:,sl,None]
        weight_sum[:,a:b]+=weight[:,sl]
    result=np.zeros((height,width,3),np.uint8)
    combined=np.clip(acc/np.maximum(weight_sum[:,:,None],1e-8),0,255).astype(np.uint8)
    result[top:bottom]=combined[:,margin:margin+width]
    output.parent.mkdir(parents=True,exist_ok=True)
    if not cv2.imwrite(str(output),result,[cv2.IMWRITE_JPEG_QUALITY,96]):
        raise RuntimeError(f'Cannot write {output}')
    output.with_suffix('.json').write_text(json.dumps({'projection':'calibrated cylinder, gravity aligned','seam':seam,'width':width,'height':height,'source':str(timeline),'yawOffsetRadians':yaw_offset,'frames':len(frames),'translationCompensated':False,'elapsedSeconds':time.perf_counter()-start},indent=2)+'\n')
    print(f'{output.name}: {time.perf_counter()-start:.2f}s',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--timeline',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--seam',choices=['feather','graphcut'],default='feather');a=p.parse_args();render(a.timeline,a.output,a.seam)
