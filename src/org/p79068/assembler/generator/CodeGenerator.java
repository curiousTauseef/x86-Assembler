package org.p79068.assembler.generator;

import java.util.List;
import java.util.Map;

import org.p79068.assembler.Program;
import org.p79068.assembler.operand.Immediate;
import org.p79068.assembler.operand.ImmediateValue;
import org.p79068.assembler.operand.Memory;
import org.p79068.assembler.operand.Operand;
import org.p79068.assembler.operand.Register;
import org.p79068.assembler.operand.Register32;


final class CodeGenerator {
	
	public static int getMachineCodeLength(InstructionPatternTable table, String mnemonic, List<Operand> operands) {
		InstructionPattern pat = table.match(mnemonic, operands);
		int length = 0;
		
		if (pat.operandSizeMode == OperandSizeMode.MODE16)
			length++;
		
		length += pat.opcodes.length;
		
		if (pat.options.size() == 1 && pat.options.get(0) instanceof ModRM)
			length += getModRMBytesLength((ModRM)pat.options.get(0), operands);
		
		for (int i = 0; i < pat.operands.size(); i++) {
			OperandPattern slot = pat.operands.get(i);
			if (slot == OperandPattern.IMM8 || slot == OperandPattern.IMM8S || slot == OperandPattern.REL8)
				length += 1;
			else if (slot == OperandPattern.IMM16 || slot == OperandPattern.REL16)
				length += 2;
			else if (slot == OperandPattern.IMM32 || slot == OperandPattern.REL32)
				length += 4;
		}
		
		return length;
	}
	
	
	private static int getModRMBytesLength(ModRM option, List<Operand> operands) {
		Operand rm = operands.get(option.rmOperandIndex);
		
		if (rm instanceof Register) {
			return 1;
			
		} else if (rm instanceof Memory) {
			Memory m = (Memory)rm;
			Immediate disp = m.displacement;
			
			if (m.base == null && m.index == null)  // disp32
				return 5;
			else if (m.base != Register32.ESP && m.base != Register32.EBP && m.index == null && disp instanceof ImmediateValue && ((ImmediateValue)disp).isZero())  // eax, ecx, edx, ebx, esi, edi
				return 1;
			else if (m.base != Register32.ESP && m.index == null && disp instanceof ImmediateValue && ((ImmediateValue)disp).isSigned8Bit())  // (eax, ecx, edx, ebx, ebp, esi, edi) + disp8
				return 2;
			else if (m.base != Register32.ESP && m.index == null)  // (eax, ecx, edx, ebx, ebp, esi, edi) + disp32
				return 5;
			else {  // SIB
				if (m.base == null)  // index*scale + disp32
					return 6;
				else if (m.base != Register32.EBP && disp instanceof ImmediateValue && ((ImmediateValue)disp).isZero())  // (eax, ecx, edx, ebx, esp, esi, edi) + index*scale
					return 2;
				else if (disp instanceof ImmediateValue && ((ImmediateValue)disp).isSigned8Bit())  // base + index*scale + disp8
					return 3;
				else  // base + index*scale + disp32
					return 6;
			}
			
		} else
			throw new AssertionError("Not a register or memory operand");
	}
	
	
	public static byte[] makeMachineCode(InstructionPatternTable table, String mnemonic, List<Operand> operands, Program program, Map<String,Integer> labelOffsets, int offset) {
		// Get matching instruction pattern
		InstructionPattern pat = table.match(mnemonic, operands);
		
		// Initialize blank result
		byte[] result = new byte[0];
		
		// Append operand size override prefix if necessary
		if (pat.operandSizeMode == OperandSizeMode.MODE16)
			result = concatenate(result, new byte[]{0x66});
		
		// Process register-in-opcode option
		byte[] opcodes = pat.opcodes;
		if (pat.options.size() == 1 && pat.options.get(0) instanceof RegisterInOpcode) {
			RegisterInOpcode option = (RegisterInOpcode)pat.options.get(0);
			opcodes = opcodes.clone();
			opcodes[opcodes.length - 1] += ((Register)operands.get(option.operandIndex)).getRegisterNumber();
		}
		
		// Append opcode
		result = concatenate(result, opcodes);
		
		// Append ModR/M and SIB bytes if necessary
		if (pat.options.size() == 1 && pat.options.get(0) instanceof ModRM)
			result = concatenate(result, makeModRMBytes((ModRM)pat.options.get(0), operands, program, labelOffsets));
		
		// Append immediate operands if necessary
		for (int i = 0; i < pat.operands.size(); i++) {
			OperandPattern slot = pat.operands.get(i);
			
			if (slot == OperandPattern.IMM8 || slot == OperandPattern.IMM8S || slot == OperandPattern.IMM16 || slot == OperandPattern.IMM32 || slot == OperandPattern.REL8 || slot == OperandPattern.REL16 || slot == OperandPattern.REL32) {
				
				ImmediateValue value = ((Immediate)operands.get(i)).getValue(labelOffsets);
				
				if (slot == OperandPattern.REL8 || slot == OperandPattern.REL16 || slot == OperandPattern.REL32)
					value = new ImmediateValue(value.getValue(labelOffsets).getValue() - offset - getMachineCodeLength(table, mnemonic, operands));
				
				// Encode signed or unsigned
				if (slot == OperandPattern.IMM8) {
					result = concatenate(result, value.to1Byte());
				} else if (slot == OperandPattern.IMM16) {
					result = concatenate(result, value.to2Bytes());
				} else if (slot == OperandPattern.IMM32 || slot == OperandPattern.REL32) {
					result = concatenate(result, value.to4Bytes());
				}
				
				// Encode signed
				else if (slot == OperandPattern.IMM8S || slot == OperandPattern.REL8) {
					if (!value.isSigned8Bit())
						throw new RuntimeException("Not a signed 8-bit immediate operand");
					result = concatenate(result, value.to1Byte());
				} else if (slot == OperandPattern.REL16) {
					if (!value.isSigned16Bit())
						throw new RuntimeException("Not a signed 16-bit immediate operand");
					result = concatenate(result, value.to2Bytes());
				} else {
					throw new AssertionError();
				}
			}
		}
		
		// Return machine code sequence
		return result;
	}
	
	
	private static byte[] makeModRMBytes(ModRM option, List<Operand> operands, Program program, Map<String,Integer> labelOffsets) {
		Operand rm = operands.get(option.rmOperandIndex);
		int mod;
		int rmvalue;
		byte[] rest;
		
		if (rm instanceof Register) {
			mod = 3;
			rmvalue = ((Register)rm).getRegisterNumber();
			rest = new byte[0];
			
		} else if (rm instanceof Memory) {
			Memory m = (Memory)rm;
			ImmediateValue disp = m.displacement.getValue(labelOffsets);
			
			if (m.base == null && m.index == null) {  // disp32
				mod = 0;
				rmvalue = 5;
				rest = disp.to4Bytes();
				
			} else if (m.base != Register32.ESP && m.base != Register32.EBP && m.index == null && m.displacement instanceof ImmediateValue && disp.isZero()) {  // eax, ecx, edx, ebx, esi, edi
				mod = 0;
				rmvalue = m.base.getRegisterNumber();
				rest = new byte[0];
				
			} else if (m.base != Register32.ESP && m.index == null && m.displacement instanceof ImmediateValue && disp.isSigned8Bit()) {  // (eax, ecx, edx, ebx, ebp, esi, edi) + disp8
				mod = 1;
				rmvalue = m.base.getRegisterNumber();
				rest = disp.to1Byte();
				
			} else if (m.base != Register32.ESP && m.index == null) {  // (eax, ecx, edx, ebx, ebp, esi, edi) + disp32
				mod = 2;
				rmvalue = m.base.getRegisterNumber();
				rest = disp.to4Bytes();
				
			} else {  // SIB
				rmvalue = 4;
				
				if (m.base == null) {  // index*scale + disp32
					mod = 0;
					rest = disp.to4Bytes();
					
				} else if (m.base != Register32.EBP && m.displacement instanceof ImmediateValue && disp.isZero()) {  // (eax, ecx, edx, ebx, esp, esi, edi) + index*scale
					mod = 0;
					rest = new byte[0];
					
				} else if (m.displacement instanceof ImmediateValue && disp.isSigned8Bit()) {  // base + index*scale + disp8
					mod = 1;
					rest = disp.to1Byte();
					
				} else {  // base + index*scale + disp32
					mod = 2;
					rest = disp.to4Bytes();
				}
				
				byte[] sib = makeSIBByte(m);
				rest = concatenate(sib, rest);
			}
			
		} else
			throw new AssertionError("Not a register or memory operand");
		
		// Set reg/op value
		int regopvalue;
		if (option.regOpcodeOperandIndex < 10) {
			Register regop = (Register)operands.get(option.regOpcodeOperandIndex);
			regopvalue = regop.getRegisterNumber();
		} else
			regopvalue = option.regOpcodeOperandIndex - 10;
		
		// Make ModR/M byte
		byte[] modrm = makeModRMByte(mod, regopvalue, rmvalue);
		
		// Concatenate and return
		return concatenate(modrm, rest);
	}
	
	
	private static byte[] makeModRMByte(int mod, int regop, int rm) {
		if (mod < 0 || mod >= 4 || regop < 0 || regop >= 8 || rm < 0 || rm >= 8)
			throw new AssertionError("Invalid ModR/M fields");
		return new byte[]{(byte)(mod << 6 | regop << 3 | rm << 0)};
	}
	
	
	private static byte[] makeSIBByte(Memory mem) {
		int scale = getScaleNumber(mem.scale);
		int index = getIndexNumber(mem.index);
		int base = getBaseNumber(mem.base);
		return new byte[]{(byte)(scale << 6 | index << 3 | base << 0)};
	}
	
	
	private static int getBaseNumber(Register32 base) {
		if (base != null)
			return base.getRegisterNumber();
		else
			return 5;
	}
	
	
	private static int getIndexNumber(Register32 index) {
		if (index == Register32.ESP)
			throw new IllegalArgumentException("ESP register not allowed");
		if (index != null)
			return index.getRegisterNumber();
		else
			return 4;
	}
	
	
	private static int getScaleNumber(int scale) {
		switch (scale) {
			case 1:  return 0;
			case 2:  return 1;
			case 4:  return 2;
			case 8:  return 3;
			default:  throw new AssertionError("Invalid scale");
		}
	}
	
	
	private static byte[] concatenate(byte[]... arrays) {
		int totalLength = 0;
		for (byte[] b : arrays)
			totalLength += b.length;
		
		byte[] result = new byte[totalLength];
		int offset = 0;
		for (byte[] b : arrays) {
			System.arraycopy(b, 0, result, offset, b.length);
			offset += b.length;
		}
		return result;
	}
	
	
	
	/**
	 * Not instantiable.
	 */
	private CodeGenerator() {}
	
}
