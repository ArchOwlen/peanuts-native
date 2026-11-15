# PEANUTS: new test file (buffer textures as texel buffers, vk_android_spv.py buffer_textures_split)
"""A buffer texture read through a texture slot (vk_android_spv.py buffer_textures_split): the translator declares it as a combined image and sampler of Dim Buffer, then takes the
image back with OpImage before each fetch and size query. Vulkan has no combined descriptor of a texel buffer, so the sampled image type becomes the image (a UNIFORM_TEXEL_BUFFER
binding with no sampler), each load of it gives the image and each OpImage of it a copy; a module that uses the loaded value another way is left as it is. Modules are assembled with
spirv-as and checked with spirv-val and the tool's own reflection (resources)."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[0] / 'tools'))
import vk_android_spv as spv  # noqa: E402

HEAD = '''
               OpCapability Shader
               OpCapability SampledBuffer
               OpCapability ImageQuery
               OpMemoryModel Logical GLSL450
               OpEntryPoint GLCompute %main "main" %tb
               OpExecutionMode %main LocalSize 1 1 1
               OpDecorate %tb DescriptorSet 0
               OpDecorate %tb Binding 3
       %void = OpTypeVoid
         %fn = OpTypeFunction %void
      %float = OpTypeFloat 32
        %int = OpTypeInt 32 1
      %v4f = OpTypeVector %float 4
       %img = OpTypeImage %float Buffer 0 0 0 1 Unknown
        %si = OpTypeSampledImage %img
      %psi = OpTypePointer UniformConstant %si
        %c0 = OpConstant %int 0
        %tb = OpVariable %psi UniformConstant
'''
GOOD = HEAD + '''
       %main = OpFunction %void None %fn
          %l = OpLabel
          %s = OpLoad %si %tb
          %i = OpImage %img %s
          %t = OpImageFetch %v4f %i %c0
         %sz = OpImageQuerySize %int %i
               OpReturn
               OpFunctionEnd
'''
# a separate texel buffer array and sampler array joined by OpSampledImage, as Ryujinx's fallback arrays (tex_dyn_slots) are read: the join becomes a copy of the image
DYN = '''
               OpCapability Shader
               OpCapability SampledBuffer
               OpMemoryModel Logical GLSL450
               OpEntryPoint GLCompute %main "main" %tb %sp
               OpExecutionMode %main LocalSize 1 1 1
               OpDecorate %tb DescriptorSet 0
               OpDecorate %tb Binding 3
               OpDecorate %sp DescriptorSet 0
               OpDecorate %sp Binding 4
       %void = OpTypeVoid
         %fn = OpTypeFunction %void
      %float = OpTypeFloat 32
        %int = OpTypeInt 32 1
       %uint = OpTypeInt 32 0
      %v4f = OpTypeVector %float 4
       %img = OpTypeImage %float Buffer 0 0 0 1 Unknown
        %si = OpTypeSampledImage %img
        %c0 = OpConstant %int 0
        %c4 = OpConstant %uint 4
       %arr = OpTypeArray %img %c4
      %parr = OpTypePointer UniformConstant %arr
      %pimg = OpTypePointer UniformConstant %img
       %smp = OpTypeSampler
      %psmp = OpTypePointer UniformConstant %smp
        %tb = OpVariable %parr UniformConstant
        %sp = OpVariable %psmp UniformConstant
       %main = OpFunction %void None %fn
          %l = OpLabel
          %p = OpAccessChain %pimg %tb %c0
          %a = OpLoad %img %p
          %q = OpLoad %smp %sp
          %s = OpSampledImage %si %a %q
          %i = OpImage %img %s
          %t = OpImageFetch %v4f %i %c0
               OpReturn
               OpFunctionEnd
'''
# the sampled image reaches a function call: not rewritten
BAD = HEAD.replace('%psi = OpTypePointer', '%fn2 = OpTypeFunction %v4f %si\n      %psi = OpTypePointer') + '''
       %main = OpFunction %void None %fn
          %l = OpLabel
          %s = OpLoad %si %tb
          %r = OpFunctionCall %v4f %f %s
               OpReturn
               OpFunctionEnd
          %f = OpFunction %v4f None %fn2
          %p = OpFunctionParameter %si
         %l2 = OpLabel
          %i = OpImage %img %p
          %t = OpImageFetch %v4f %i %c0
               OpReturnValue %t
               OpFunctionEnd
'''


def assemble(d, text):
    (d / 'a.spvasm').write_text(text)
    subprocess.run(['spirv-as', '--target-env', 'vulkan1.3', str(d / 'a.spvasm'), '-o', str(d / 'a.spv')], check=True)
    return spv.Module((d / 'a.spv').read_bytes())


@unittest.skipUnless(shutil.which('spirv-as') and shutil.which('spirv-val'), 'needs SPIRV-Tools')
class BufferTextureSplit(unittest.TestCase):
    def test_combined_becomes_texel_buffer(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = assemble(d, GOOD)
            self.assertEqual(m.resources()[0]['desc'], spv.VK_COMBINED)
            self.assertEqual(spv.buffer_textures_split(m), 1)
            r = m.resources()
            self.assertEqual((r[0]['desc'], r[0]['binding'], r[0]['dim']['dim']), (spv.VK_UNIFORM_TEXEL, 3, spv.DIM_BUFFER))
            ops = [x[0] & 0xffff for x in m.ins]
            self.assertEqual(ops.count(spv.OP_IMAGE), 0)
            self.assertEqual(ops.count(spv.OP_COPY_OBJECT), 1)
            (d / 'b.spv').write_bytes(m.data())
            v = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'b.spv')], capture_output=True, text=True)
            self.assertEqual(v.returncode, 0, v.stdout + v.stderr)
            self.assertEqual(spv.buffer_textures_split(spv.Module(m.data())), 0)   # nothing left to do

    def test_image_and_sampler_join(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = assemble(d, DYN)
            self.assertEqual(spv.buffer_textures_split(m), 1)
            ops = [x[0] & 0xffff for x in m.ins]
            self.assertEqual((ops.count(spv.OP_SAMPLED_IMAGE), ops.count(27), ops.count(spv.OP_IMAGE)), (0, 0, 0))   # no OpSampledImage, no sampled image type, no OpImage
            (d / 'b.spv').write_bytes(m.data())
            v = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'b.spv')], capture_output=True, text=True)
            self.assertEqual(v.returncode, 0, v.stdout + v.stderr)

    def test_other_uses_are_left(self):
        with tempfile.TemporaryDirectory() as d:
            m = assemble(Path(d), BAD)
            self.assertEqual(spv.buffer_textures_split(m), 0)
            self.assertEqual(m.resources()[0]['desc'], spv.VK_COMBINED)


if __name__ == '__main__':
    unittest.main()
