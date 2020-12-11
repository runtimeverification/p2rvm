#!/usr/bin/env python

import glob
import os.path
import shutil
import sys

if __name__ == "__main__":
    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import tools

def copyDep(dep_dir, java_dir):
    tools.progress("Copy Dependency")
    for f in glob.glob(os.path.join(dep_dir, "*.java")):
        shutil.copy(f, java_dir)

def copyAspectJ(logger_dir, aspectj_dir):
    tools.progress("Copy AspectJ")
    for f in glob.glob(os.path.join(logger_dir, "*.aj")):
        shutil.copy(f, aspectj_dir)

def build(logger_dir, aspectj_dir, java_dir):
    copyAspectJ(logger_dir, aspectj_dir)
    dep_dir = os.path.join(logger_dir, "dep")
    copyDep(dep_dir, java_dir)

def removeAll(pattern):
    for f in glob.glob(pattern):
        os.remove(f)

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    gen_src_dir = os.path.join(script_dir, "target", "generated-sources")
    logger_dir = os.path.join(script_dir, "logger")

    aspectj_dir = os.path.join(gen_src_dir, "aspectJ")
    if not os.path.exists(aspectj_dir):
        os.makedirs(aspectj_dir)

    java_dir = os.path.join(gen_src_dir, "java")
    if not os.path.exists(java_dir):
        os.makedirs(java_dir)


    try:
        tools.runInDirectory(
            script_dir,
            lambda: build(logger_dir, aspectj_dir, java_dir))
    except BaseException as e:
        raise e

if __name__ == "__main__":
    main()
