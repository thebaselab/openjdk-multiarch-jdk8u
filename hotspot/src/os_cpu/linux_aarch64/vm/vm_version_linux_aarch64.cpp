/*
 * Copyright (c) 2006, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "runtime/os.hpp"
#include "vm_version_aarch64.hpp"

#include <asm/hwcap.h>
#include <sys/auxv.h>
#include <sys/prctl.h>

#ifndef HWCAP_AES
#define HWCAP_AES   (1<<3)
#endif

#ifndef HWCAP_PMULL
#define HWCAP_PMULL (1<<4)
#endif

#ifndef HWCAP_SHA1
#define HWCAP_SHA1  (1<<5)
#endif

#ifndef HWCAP_SHA2
#define HWCAP_SHA2  (1<<6)
#endif

#ifndef HWCAP_CRC32
#define HWCAP_CRC32 (1<<7)
#endif

#ifndef HWCAP_ATOMICS
#define HWCAP_ATOMICS (1<<8)
#endif

#ifndef HWCAP_DCPOP
#define HWCAP_DCPOP (1<<16)
#endif

#ifndef HWCAP_SHA3
#define HWCAP_SHA3 (1 << 17)
#endif

#ifndef HWCAP_SHA512
#define HWCAP_SHA512 (1 << 21)
#endif

#ifndef HWCAP_SVE
#define HWCAP_SVE (1 << 22)
#endif

#ifndef HWCAP2_SVE2
#define HWCAP2_SVE2 (1 << 1)
#endif

#ifndef PR_SVE_GET_VL
// For old toolchains which do not have SVE related macros defined.
#define PR_SVE_SET_VL   50
#define PR_SVE_GET_VL   51
#endif

int VM_Version::get_current_sve_vector_length() {
  assert(_cpuFeatures & CPU_SVE, "should not call this");
  return prctl(PR_SVE_GET_VL);
}

int VM_Version::set_and_get_current_sve_vector_lenght(int length) {
  assert(_cpuFeatures & CPU_SVE, "should not call this");
  int new_length = prctl(PR_SVE_SET_VL, length);
  return new_length;
}

void VM_Version::get_os_cpu_info() {

  uint64_t auxv = getauxval(AT_HWCAP);
  uint64_t auxv2 = getauxval(AT_HWCAP2);

  STATIC_ASSERT(CPU_FP      == HWCAP_FP);
  STATIC_ASSERT(CPU_ASIMD   == HWCAP_ASIMD);
  STATIC_ASSERT(CPU_EVTSTRM == HWCAP_EVTSTRM);
  STATIC_ASSERT(CPU_AES     == HWCAP_AES);
  STATIC_ASSERT(CPU_PMULL   == HWCAP_PMULL);
  STATIC_ASSERT(CPU_SHA1    == HWCAP_SHA1);
  STATIC_ASSERT(CPU_SHA2    == HWCAP_SHA2);
  STATIC_ASSERT(CPU_CRC32   == HWCAP_CRC32);
  STATIC_ASSERT(CPU_LSE     == HWCAP_ATOMICS);
  STATIC_ASSERT(CPU_DCPOP   == HWCAP_DCPOP);
  STATIC_ASSERT(CPU_SHA512  == HWCAP_SHA512);
  STATIC_ASSERT(CPU_SVE     == HWCAP_SVE);
  _cpuFeatures = auxv & (
      HWCAP_FP      |
      HWCAP_ASIMD   |
      HWCAP_EVTSTRM |
      HWCAP_AES     |
      HWCAP_PMULL   |
      HWCAP_SHA1    |
      HWCAP_SHA2    |
      HWCAP_CRC32   |
      HWCAP_ATOMICS |
      HWCAP_DCPOP   |
      HWCAP_SHA512  |
      HWCAP_SVE);

  if (auxv2 & HWCAP2_SVE2) _cpuFeatures |= CPU_SVE2;

  uint64_t ctr_el0;
  uint64_t dczid_el0;
  __asm__ (
    "mrs %0, CTR_EL0\n"
    "mrs %1, DCZID_EL0\n"
    : "=r"(ctr_el0), "=r"(dczid_el0)
  );

  _icache_line_size = (1 << (ctr_el0 & 0x0f)) * 4;
  _dcache_line_size = (1 << ((ctr_el0 >> 16) & 0x0f)) * 4;

  if (!(dczid_el0 & 0x10)) {
    _zva_length = 4 << (dczid_el0 & 0xf);
  }

  int cpu_lines = 0;
  if (FILE *f = fopen("/proc/cpuinfo", "r")) {
    // need a large buffer as the flags line may include lots of text
    char buf[1024], *p;
    while (fgets(buf, sizeof (buf), f) != NULL) {
      if ((p = strchr(buf, ':')) != NULL) {
        long v = strtol(p+1, NULL, 0);
        if (strncmp(buf, "CPU implementer", sizeof "CPU implementer" - 1) == 0) {
          _cpu = v;
          cpu_lines++;
        } else if (strncmp(buf, "CPU variant", sizeof "CPU variant" - 1) == 0) {
          _variant = v;
        } else if (strncmp(buf, "CPU part", sizeof "CPU part" - 1) == 0) {
          if (_model != v)  _model2 = _model;
          _model = v;
        } else if (strncmp(buf, "CPU revision", sizeof "CPU revision" - 1) == 0) {
          _revision = v;
        } else if (strncmp(buf, "flags", sizeof("flags") - 1) == 0) {
          if (strstr(p+1, "dcpop")) {
            guarantee(_cpuFeatures & CPU_DCPOP, "dcpop availability should be consistent");
          }
        }
      }
    }
    fclose(f);
  }
  guarantee(cpu_lines == os::processor_count(), "core count should be consistent");
}
