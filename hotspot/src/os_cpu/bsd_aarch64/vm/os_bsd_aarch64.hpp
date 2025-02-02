/*
 * Copyright (c) 1999, 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef OS_CPU_BSD_AARCH64_VM_OS_BSD_AARCH64_HPP
#define OS_CPU_BSD_AARCH64_VM_OS_BSD_AARCH64_HPP

#include "tcg-apple-jit.h"

  static void setup_fpu();

  static bool is_allocatable(size_t bytes);

  // Used to register dynamic code cache area with the OS
  // Note: Currently only used in 64 bit Windows implementations
  static bool register_code_area(char *low, char *high) { return true; }

  // Atomically copy 64 bits of data
  static void atomic_copy64(volatile void *src, volatile void *dst) {
#if defined(PPC) && !defined(_LP64)
    double tmp;
    asm volatile ("lfd  %0, 0(%1)\n"
                  "stfd %0, 0(%2)\n"
                  : "=f"(tmp)
                  : "b"(src), "b"(dst));
#elif defined(S390) && !defined(_LP64)
    double tmp;
    asm volatile ("ld  %0, 0(%1)\n"
                  "std %0, 0(%2)\n"
                  : "=r"(tmp)
                  : "a"(src), "a"(dst));
#else
    *(jlong *) dst = *(jlong *) src;
#endif
  }

private:

  static void current_thread_enable_wx_impl(WXMode mode) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunguarded-availability-new"
    // pthread_jit_write_protect_np(mode == WXExec ? true : false);
    jit_write_protect(mode == WXExec);
#pragma clang diagnostic pop
  }

public:

#endif // OS_CPU_BSD_AARCH64_VM_OS_BSD_AARCH64_HPP
