# PEANUTS: a test of the Android shader chain's promote_locals on an image write (OCTOPATH TRAVELER 0, issue #9)
"""promote_locals (vk_android_spv.py) on a compute shader whose storage image write takes its coordinate from a Function variable the translator stored once: the load is
replaced by the stored value in every instruction, the OpImageWrite's coordinate and an OpAtomicStore's value included. _remap_operands read an instruction with no result id
from its word 3 on (OpImageWrite: image, coordinate, texel, so it skipped the coordinate), which left the coordinate naming a load that promote_locals had deleted: spirv-val
said "ID has not been defined" for 2,142 of OCTOPATH TRAVELER 0's 2,521 compute shaders (those that write an image at a coordinate from a register)."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[0] / 'tools'))
import vk_android_spv as spv  # noqa: E402

ASM = '''
               OpCapability Shader
               OpCapability StorageImageWriteWithoutFormat
               OpMemoryModel Logical GLSL450
               OpEntryPoint GLCompute %main "main" %gid %img %buf
               OpExecutionMode %main LocalSize 64 1 1
               OpDecorate %gid BuiltIn GlobalInvocationId
               OpDecorate %img DescriptorSet 0
               OpDecorate %img Binding 0
               OpDecorate %buf DescriptorSet 0
               OpDecorate %buf Binding 1
               OpDecorate %blk Block
               OpMemberDecorate %blk 0 Offset 0
               OpDecorate %arr ArrayStride 4
       %void = OpTypeVoid
         %fn = OpTypeFunction %void
       %uint = OpTypeInt 32 0
        %int = OpTypeInt 32 1
      %float = OpTypeFloat 32
      %v4f = OpTypeVector %float 4
      %v3u = OpTypeVector %uint 3
       %arr = OpTypeRuntimeArray %uint
       %blk = OpTypeStruct %arr
   %pblk = OpTypePointer StorageBuffer %blk
   %pu_sb = OpTypePointer StorageBuffer %uint
      %pgid = OpTypePointer Input %v3u
     %pint_f = OpTypePointer Function %int
    %pu_f = OpTypePointer Function %uint
       %timg = OpTypeImage %float 2D 0 0 0 2 Unknown
      %pimg = OpTypePointer UniformConstant %timg
       %v2i = OpTypeVector %int 2
        %c0 = OpConstant %int 0
       %cu0 = OpConstant %uint 0
       %cu1 = OpConstant %uint 1
       %cu2 = OpConstant %uint 2
      %cu72 = OpConstant %uint 72
      %fone = OpConstant %float 1
      %texel = OpConstantComposite %v4f %fone %fone %fone %fone
       %gid = OpVariable %pgid Input
       %img = OpVariable %pimg UniformConstant
       %buf = OpVariable %pblk StorageBuffer
       %main = OpFunction %void None %fn
          %l = OpLabel
         %vx = OpVariable %pint_f Function
         %vu = OpVariable %pu_f Function
         %p0 = OpAccessChain %pgid %gid
         %g = OpLoad %v3u %p0
         %gx = OpCompositeExtract %uint %g 0
         %xi = OpBitcast %int %gx
               OpStore %vx %xi
               OpStore %vu %gx
               OpBranch %b
          %b = OpLabel
         %lx = OpLoad %int %vx
         %lu = OpLoad %uint %vu
         %im = OpLoad %timg %img
         %co = OpCompositeConstruct %v2i %lx %lx
               OpImageWrite %im %co %texel
         %pc = OpAccessChain %pu_sb %buf %c0 %cu0
               OpAtomicStore %pc %cu1 %cu0 %lu
               OpReturn
               OpFunctionEnd
'''

# the coordinate is the promoted load itself (a 1D image): the form Octopath's shaders take, which the vector's OpCompositeConstruct above hides
ASM_DIRECT = ASM.replace(
    '%timg = OpTypeImage %float 2D 0 0 0 2 Unknown', '%timg = OpTypeImage %float 1D 0 0 0 2 Unknown').replace(
    '         %co = OpCompositeConstruct %v2i %lx %lx\n', '').replace('OpImageWrite %im %co %texel', 'OpImageWrite %im %lx %texel').replace(
    'OpCapability StorageImageWriteWithoutFormat', 'OpCapability StorageImageWriteWithoutFormat\n               OpCapability Image1D')


def assemble(text, d):
    (d / 'a.spvasm').write_text(text)
    subprocess.run(['spirv-as', '--target-env', 'vulkan1.3', str(d / 'a.spvasm'), '-o', str(d / 'a.spv')], check=True)
    return (d / 'a.spv').read_bytes()


def validate(data, d):
    (d / 'b.spv').write_bytes(data)
    return subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'b.spv')], capture_output=True, text=True)


@unittest.skipUnless(shutil.which('spirv-as') and shutil.which('spirv-val'), 'needs SPIRV-Tools')
class PromoteImageWrite(unittest.TestCase):
    def check(self, text):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            data = assemble(text, d)
            self.assertEqual(validate(data, d).returncode, 0)   # as the assembly stands
            m = spv.Module(data)
            spv.promote_locals(m)
            r = validate(m.data(), d)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertFalse([x for x in m.ins if x[0] & 0xffff == spv.OP_VARIABLE and len(x) == 4 and x[3] == spv.SC_FUNCTION])   # both locals promoted

    def test_coordinate_of_a_vector(self):
        self.check(ASM)

    def test_coordinate_is_the_load_itself(self):
        self.check(ASM_DIRECT)

    def test_image_write_mask_literal_is_not_remapped(self):
        """a literal in the image operands mask that equals the id of a promoted load stays a literal"""
        x = [5 << 16 | spv.OP_IMAGE_WRITE, 10, 11, 12, 0x20]
        spv._remap_operands(x, {0x20: 99, 11: 55})
        self.assertEqual(x[1:], [10, 55, 12, 0x20])


if __name__ == '__main__':
    unittest.main()
