#version 150

vec4 amber_project(mat4 proj, mat4 modelView, vec3 pos) {
    return proj * modelView * vec4(pos, 1.0);
}
