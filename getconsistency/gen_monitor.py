#!/usr/bin/env python

import glob
import os.path
import shutil
import sys

if __name__ == "__main__":
    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import tools

def readFile(name):
    with open(name, "rt") as f:
        return ''.join(f)

def writeFile(name, contents):
    with open(name, "wt") as f:
        f.write(contents)

def runPc(pcompiler_dir, arguments):
    tools.runNoError(["dotnet", os.path.join(pcompiler_dir, "Bld", "Drops", "Release", "Binaries", "netcoreapp3.1", "P.dll")] + arguments)

def translate(pcompiler_dir, p_spec_dir, gen_monitor_dir):
    tools.progress("Run the PCompiler...")
    p_specs = glob.glob(os.path.join(p_spec_dir, "*.p"))
    runPc(pcompiler_dir, p_specs + ["-g:RVM", "-o:%s" % gen_monitor_dir])

def runMonitor(rvmonitor_bin, generated_file_dir):
    tools.progress("Run RVMonitor")
    monitor_binary = os.path.join(rvmonitor_bin, "rv-monitor")
    rvm_files = glob.glob(os.path.join(generated_file_dir, "*.rvm"))
    for rvm_file in rvm_files:
        tools.runNoError([monitor_binary, "-merge", rvm_file])

def copyDep(dep_dir, generated_file_dir):
    tools.progress("Copy Dep")
    for f in glob.glob(os.path.join(dep_dir, "*.java")):
        shutil.copy(f, generated_file_dir)

def compileJava(generated_file_dir):
    java_files = glob.glob(os.path.join(generated_file_dir, "*.java"))
    tools.runNoError(["javac"] + java_files + ["-d", "./"])

def build(pcompiler_dir, rvmonitor_bin, p_spec_dir, generated_file_dir, dep_dir):
    translate(pcompiler_dir, p_spec_dir, generated_file_dir)
    runMonitor(rvmonitor_bin, generated_file_dir)
    copyDep(dep_dir, generated_file_dir)
    compileJava(generated_file_dir)

def removeAll(pattern):
    for f in glob.glob(pattern):
        os.remove(f)

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    ext_dir = os.path.join(os.path.dirname(script_dir), "ext")
    pcompiler_dir = os.path.join(ext_dir, "P")
    rvmonitor_bin = os.path.join(ext_dir, "rv-monitor", "target", "release", "rv-monitor", "bin")
    p_spec_dir = os.path.join(script_dir, "monitor")
    dep_dir = os.path.join(p_spec_dir, "dep")

    generated_file_dir = os.path.join(p_spec_dir, "generated")
    if not os.path.exists(generated_file_dir):
        os.makedirs(generated_file_dir)

    try:
        tools.runInDirectory(
            script_dir,
            lambda: build(pcompiler_dir, rvmonitor_bin, p_spec_dir, generated_file_dir, dep_dir))
    except BaseException as e:
        raise e

if __name__ == "__main__":
    main()
