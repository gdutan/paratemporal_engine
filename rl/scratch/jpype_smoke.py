import os, jpype, jpype.imports
root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
cp = [os.path.join(root, "build", "library", "classes"), os.path.join(root, "lib", "ms4-core.jar")]
jpype.startJVM(classpath=cp)
from paradevs.engine import ParaEngine, ParaSpec, Transducer
from java.util.function import Supplier
from examples.tutorial import ServiceLine
from jpype import JImplements, JOverride

@JImplements(Transducer)
class Served:
    @JOverride
    def initial(self): return jpype.JInt(0)
    @JOverride
    def record(self, xi, t, outs):
        n = int(xi)
        for e in outs:
            if str(e.port) == "done": n += 1
        return jpype.JInt(n)

@JImplements(Supplier)
class Factory:
    @JOverride
    def get(self): return ServiceLine(3)

res = ParaEngine.run(ParaSpec.of(Factory(), Served()))
for o, w in res.distribution().entrySet():
    print("served =", o.xi, " status =", o.status, " weight =", round(float(w), 6))
print("stats:", res.stats())
