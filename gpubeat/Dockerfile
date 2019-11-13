FROM nvidia/cuda:9.0-base-centos7

RUN yum repolist && yum install -y make cmake curl git vim

RUN rpm --import https://mirror.go-repo.io/centos/RPM-GPG-KEY-GO-REPO && \
    curl -s https://mirror.go-repo.io/centos/go-repo.repo | tee /etc/yum.repos.d/go-repo.repo && \
    yum repolist && \
    yum install -y golang 

WORKDIR /root
ENV WORKSPACE /root/beats_dev
ENV GOPATH $WORKSPACE

RUN git clone https://github.com/elastic/beats ${GOPATH}/src/github.com/elastic/beats --branch 7.3 && \ 
    mkdir -p $WORKSPACE/src/github.com/ebay && \
    cd $WORKSPACE/src/github.com/ebay && \
    git clone https://github.com/eBay/nvidiagpubeat --branch withBeats7.3 && \
    cd $WORKSPACE/src/github.com/ebay/nvidiagpubeat && \
    make setup -j32&& \
    make -j32

ENV RUNPATH $WORKSPACE/src/github.com/ebay/nvidiagpubeat



