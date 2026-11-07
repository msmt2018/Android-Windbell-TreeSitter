
#Caution‼️: Generating files will consume 60-70GB of space

import os
import random

def generate_cpp_chunk(func_id):
    # 使用正确的双大括号转义：{{ 变为 {， }} 变为 }
    return (
        f"void b_{func_id}(int* p, double v) {{\n"
        f"    if(!p) return;\n"
        f"    double r = v * {random.randint(10, 99)}.0;\n"
        f"    if(r > 50.0) {{ *p += (int)r; }} else {{ *p -= {random.randint(1, 10)}; }}\n"
        f"}}\n\n"
    )

def generate_java_chunk(method_id):
    return (
        f"    public static synchronized void m_{method_id}(int i) {{\n"
        f"        try {{\n"
        f"            if(i > {random.randint(10, 100)}) System.out.println(i + {method_id});\n"
        f"        }} catch(Exception e) {{ }}\n"
        f"    }}\n\n"
    )

def create_fine_grained_file(lang, size_mb, filename):
    target_bytes = size_mb * 1024
    current_bytes = 0
    item_id = 0
    
    print(f"   --> 正在生成: {filename:<25} ({size_mb:>4} MB) ... ", end="", flush=True)
    
    with open(filename, "w", encoding="utf-8") as f:
        if lang == "cpp":
            header = f"/** CPP LIMIT TEST {size_mb}MB */\nnamespace LimitTest {{\n\n"
            f.write(header)
            current_bytes += len(header.encode('utf-8'))
        else:
            class_name = os.path.splitext(filename)[0]
            header = f"/** JAVA LIMIT TEST {size_mb}MB */\npublic class {class_name} {{\n\n"
            f.write(header)
            current_bytes += len(header.encode('utf-8'))
            
        while current_bytes < target_bytes:
            chunk = generate_cpp_chunk(item_id) if lang == "cpp" else generate_java_chunk(item_id)
            f.write(chunk)
            current_bytes += len(chunk.encode('utf-8'))
            item_id += 1
            
        footer = "}\n"
        f.write(footer)
        
    print(f"[完成] 实际: {os.path.getsize(filename) / (1024):.2f} MB")

if __name__ == "__main__":
    size_stairs = []
    
    # 1. 10MB 到 100MB，每 10MB 一个阶梯
    size_stairs.extend(range(10, 101, 10))
    
    # 2. 150MB 到 1000MB，每 50MB 一个阶梯
    size_stairs.extend(range(150, 1001, 50))
    
    # 3. 1100MB 到 2000MB，每 100MB 一个阶梯
    size_stairs.extend(range(1100, 2001, 100))
    
    size_stairs = sorted(list(set(size_stairs)))
    
    total_files = len(size_stairs) * 2
    estimated_space_gb = sum(size_stairs) * 1 / 1024
    
    print("==================================================================")
    print("               编辑器极限性能压测：超精细梯度生成器                 ")
    print(f"   将生成梯度数: {len(size_stairs)} 个 | 总文件数: {total_files} 个 | 预计占用空间: {estimated_space_gb:.2f} GB")
    print("==================================================================")
    
    print("\n[第一阶段] 开始批量生成高密度 C++ 细腻阶梯文件...")
    for size in size_stairs:
        fname = f"fine_test_{size:04d}mb.cpp"
        create_fine_grained_file("cpp", size, fname)
        
    print("\n[第二阶段] 开始批量生成高密度 Java 细腻阶梯文件...")
    for size in size_stairs:
        class_name = f"JavaLimit{size:04d}mb"
        fname = f"{class_name}.java"
        create_fine_grained_file("java", size, fname)
        
    print("\n==================================================================")
    print("🎉 细腻阶梯测试集已全部生成完毕！")
    print("==================================================================")
