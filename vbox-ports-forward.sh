#!/bin/bash

# forward prots of VirtualBox to local host.
 
VBoxManage modifyvm "springms" --natpf1 "tcp-port5672,tcp,localhost,5672,,5672"
VBoxManage modifyvm "springms" --natpf1 "tcp-port15672,tcp,localhost,15672,,15672"
VBoxManage modifyvm "springms" --natpf1 "tcp-port6379,tcp,localhost,6379,,6379"
VBoxManage modifyvm "springms" --natpf1 "tcp-port27017,tcp,localhost,27017,,27017"
VBoxManage modifyvm "springms" --natpf1 "tcp-port9042,tcp,localhost,9042,,9042" 
VBoxManage modifyvm "springms" --natpf1 "tcp-port8081,tcp,localhost,8081,,8081"
VBoxManage modifyvm "springms" --natpf1 "tcp-port8082,tcp,localhost,8082,,8082" 
VBoxManage modifyvm "springms" --natpf1 "tcp-port8083,tcp,localhost,8083,,8083"
VBoxManage modifyvm "springms" --natpf1 "tcp-port8000,tcp,localhost,8000,,8000" 

