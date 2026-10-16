#!/usr/bin/env python3
"""MIT. Install the public-repository pull controller as a per-user macOS LaunchAgent."""
import argparse
import os
from pathlib import Path
import platform
import pwd
import plistlib
import re
import shutil
import subprocess
import sys

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,required=True)
parser.add_argument('--repo',required=True)
args=parser.parse_args()
if platform.system()!='Darwin' or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',args.repo):
 parser.error('Requires macOS and an owner/repository identifier')
root=args.root.expanduser().resolve()
root.mkdir(parents=True,exist_ok=True,mode=0o700)
shutil.copy2(Path(__file__).with_name('manage.py'),root/'manage.py')
subprocess.run([sys.executable,str(root/'manage.py'),'init','--root',str(root)],check=True)
label='com.quantumhorizon.quantum-crews'
plist=Path.home()/'Library/LaunchAgents'/f'{label}.plist'
interpreter='/opt/homebrew/bin/python3' if Path('/opt/homebrew/bin/python3').exists() else sys.executable
config={'Label':label,'ProgramArguments':[interpreter,str(root/'manage.py'),'once','--root',str(root),'--repo',args.repo],
 'EnvironmentVariables':{'PATH':'/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin'},'RunAtLoad':True,'StartInterval':60,
 'StandardOutPath':str(root/'deploy.log'),'StandardErrorPath':str(root/'deploy.err')}
plist.parent.mkdir(parents=True,exist_ok=True)
plist.write_bytes(plistlib.dumps(config));os.chmod(plist,0o600)
domain=f'gui/{os.getuid()}'
if subprocess.run(['launchctl','print',domain],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
 domain=f'user/{os.getuid()}'
 config['LimitLoadToSessionType']='Background'
 plist.write_bytes(plistlib.dumps(config))
boot=dict(config,UserName=pwd.getpwuid(os.getuid()).pw_name,GroupName='staff')
boot.pop('LimitLoadToSessionType',None)
boot['EnvironmentVariables']=dict(config['EnvironmentVariables'],HOME=str(Path.home()))
(root/'boot-service.plist').write_bytes(plistlib.dumps(boot))
subprocess.run(['launchctl','bootout',domain,str(plist)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
subprocess.run(['launchctl','bootstrap',domain,str(plist)],check=True)
print('Launch domain:',domain)
print('For headless cold-boot startup, install the generated boot-service.plist with deploy/enable-boot.sh.')
print('Installed',label,'for',args.repo,'at',root)
